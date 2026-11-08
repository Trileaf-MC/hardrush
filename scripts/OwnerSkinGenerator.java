import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;

/**
 * Builds owner-only player skins for skeleton-type monster forms from the vanilla mob texture.
 *
 * <p>The vanilla client forces the player's base skin layer opaque
 * ({@code SkinTextureDownloader.processLegacySkin -> setNoAlpha}); transparent pixels keep
 * their RGB, which is normally black. A skeleton's see-through ribs and thin limbs therefore
 * cannot exist on the player model. Instead, every base pixel is painted on purpose: vanilla
 * bones stay as they are, the gaps between them become a dark "body cavity", and the 2-pixel
 * vanilla limbs sit in the middle of the 4-pixel player limbs with shaded sides, so they read
 * as thin bones. Ribs and spine are repeated on the jacket layer for relief in third person.
 *
 * <p>The vanilla texture is read from the local client JAR and the result is written under
 * {@code .local/}; derived Mojang art is not committed. The uploaded result is pinned in
 * {@code platforms/mc263/src/main/resources/hardrush/owner-skins.json}.
 *
 * <pre>
 * java scripts/OwnerSkinGenerator.java generate [--client-jar JAR] [--out DIR]
 * java scripts/OwnerSkinGenerator.java check SKIN.png
 * java scripts/OwnerSkinGenerator.java compare EXPECTED.png ACTUAL.png
 * </pre>
 *
 * <p>Publishing: upload {@code DIR/skeleton.png} as a classic-model skin, for example
 * {@code curl -F file=@skeleton.png -F variant=classic -F visibility=unlisted
 * https://api.mineskin.org/v2/generate}, download the returned textures.minecraft.net URL and
 * {@code compare} it with the generated file before pinning the property value.
 */
public final class OwnerSkinGenerator {
    private static final String VANILLA_SKELETON = "assets/minecraft/textures/entity/skeleton/skeleton.png";
    /** Brightness kept on the shaded sides of a thin bone limb. */
    private static final double LIMB_SHADE = 0.38;
    /** Sum of RGB below which an opaque base pixel counts as "black" in {@link #check}. */
    private static final int BLACK_RGB_SUM = 60;

    private OwnerSkinGenerator() {}

    /** A box in a skin texture: UV origin and size; {@link #faces()} follows the vanilla box UV layout. */
    private record Box(int u, int v, int w, int h, int d) {
        /** top, bottom, right(-x side), front, left(+x side), back as {x, y, width, height}. */
        int[][] faces() {
            return new int[][]{{u + d, v, w, d}, {u + d + w, v, w, d}, {u, v + d, d, h},
                {u + d, v + d, w, h}, {u + d + w, v + d, d, h}, {u + 2 * d + w, v + d, w, h}};
        }
    }

    // Vanilla skeleton (64x32): head and body match the player boxes; limbs are 2x12x2.
    private static final Box SKELETON_HEAD = new Box(0, 0, 8, 8, 8);
    private static final Box SKELETON_BODY = new Box(16, 16, 8, 12, 4);
    private static final Box SKELETON_ARM = new Box(40, 16, 2, 12, 2);
    private static final Box SKELETON_LEG = new Box(0, 16, 2, 12, 2);

    // Player skin (64x64, classic arms) base and outer layers.
    private static final Box HEAD = new Box(0, 0, 8, 8, 8);
    private static final Box BODY = new Box(16, 16, 8, 12, 4);
    private static final Box RIGHT_ARM = new Box(40, 16, 4, 12, 4);
    private static final Box LEFT_ARM = new Box(32, 48, 4, 12, 4);
    private static final Box RIGHT_LEG = new Box(0, 16, 4, 12, 4);
    private static final Box LEFT_LEG = new Box(16, 48, 4, 12, 4);
    private static final Box JACKET = new Box(16, 32, 8, 12, 4);
    private static final List<Box> BASE_BOXES = List.of(HEAD, BODY, RIGHT_ARM, LEFT_ARM, RIGHT_LEG, LEFT_LEG);

    public static void main(String[] args) throws IOException {
        if (args.length == 0) throw usage();
        switch (args[0]) {
            case "generate" -> generate(args);
            case "check" -> {
                if (args.length != 2) throw usage();
                List<String> problems = check(read(Path.of(args[1])));
                if (!problems.isEmpty()) throw new IllegalStateException(String.join("\n", problems));
                System.out.println("ok: every used base pixel is opaque and not black");
            }
            case "compare" -> {
                if (args.length != 3) throw usage();
                int differences = compare(read(Path.of(args[1])), read(Path.of(args[2])));
                if (differences != 0) throw new IllegalStateException(differences + " pixels differ");
                System.out.println("ok: identical pixels");
            }
            default -> throw usage();
        }
    }

    private static IllegalArgumentException usage() {
        return new IllegalArgumentException(
            "usage: generate [--client-jar JAR] [--out DIR] | check SKIN.png | compare EXPECTED.png ACTUAL.png");
    }

    private static void generate(String[] args) throws IOException {
        Path root = Path.of("").toAbsolutePath();
        Path jar = null;
        Path out = root.resolve(".local/owner-skins");
        for (int i = 1; i < args.length; i += 2) {
            if (i + 1 >= args.length) throw usage();
            switch (args[i]) {
                case "--client-jar" -> jar = Path.of(args[i + 1]);
                case "--out" -> out = Path.of(args[i + 1]);
                default -> throw usage();
            }
        }
        if (jar == null) jar = defaultClientJar(root);
        BufferedImage vanilla;
        try (var zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry(VANILLA_SKELETON);
            if (entry == null) throw new IllegalStateException(VANILLA_SKELETON + " not found in " + jar);
            try (var input = zip.getInputStream(entry)) { vanilla = ImageIO.read(input); }
        }
        BufferedImage skin = skeleton(vanilla);
        List<String> problems = check(skin);
        if (!problems.isEmpty()) throw new IllegalStateException(String.join("\n", problems));
        Files.createDirectories(out);
        Path file = out.resolve("skeleton.png");
        ImageIO.write(skin, "png", file.toFile());
        System.out.println("wrote " + file + " from " + jar);
    }

    private static Path defaultClientJar(Path root) throws IOException {
        var properties = new Properties();
        try (var input = Files.newInputStream(root.resolve("gradle.properties"))) { properties.load(input); }
        String version = properties.getProperty("minecraft_version");
        String gradleHome = System.getenv().getOrDefault("GRADLE_USER_HOME",
            Path.of(System.getProperty("user.home"), ".gradle").toString());
        Path jar = Path.of(gradleHome, "caches", "fabric-loom", version, "minecraft-client.jar");
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException("Client JAR not found at " + jar + "; run a Gradle build first or pass --client-jar");
        }
        return jar;
    }

    static BufferedImage skeleton(BufferedImage vanilla) {
        if (vanilla.getWidth() != 64 || vanilla.getHeight() != 32) {
            throw new IllegalStateException("Expected the 64x32 vanilla skeleton texture");
        }
        var skin = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        copyFilled(vanilla, SKELETON_HEAD, skin, HEAD);
        copyFilled(vanilla, SKELETON_BODY, skin, BODY);
        relief(vanilla, SKELETON_BODY, skin, JACKET);
        thinLimb(vanilla, SKELETON_ARM, skin, RIGHT_ARM, false);
        thinLimb(vanilla, SKELETON_ARM, skin, LEFT_ARM, true);
        thinLimb(vanilla, SKELETON_LEG, skin, RIGHT_LEG, false);
        thinLimb(vanilla, SKELETON_LEG, skin, LEFT_LEG, true);
        return skin;
    }

    /** Same-size box: vanilla pixels as they are, see-through gaps become the body cavity. */
    private static void copyFilled(BufferedImage from, Box source, BufferedImage to, Box target) {
        int[][] sourceFaces = source.faces();
        int[][] targetFaces = target.faces();
        for (int face = 0; face < 6; face++) {
            int[] s = sourceFaces[face];
            int[] t = targetFaces[face];
            for (int y = 0; y < t[3]; y++) {
                for (int x = 0; x < t[2]; x++) {
                    int pixel = from.getRGB(s[0] + x, s[1] + y);
                    to.setRGB(t[0] + x, t[1] + y, opaque(pixel) ? pixel : cavity(y, t[3]));
                }
            }
        }
    }

    /** Front and back bones (ribs, spine, pelvis) repeated on the outer layer; everything else stays clear. */
    private static void relief(BufferedImage from, Box source, BufferedImage to, Box target) {
        int[][] sourceFaces = source.faces();
        int[][] targetFaces = target.faces();
        for (int face : new int[]{3, 5}) {
            int[] s = sourceFaces[face];
            int[] t = targetFaces[face];
            for (int y = 0; y < t[3]; y++) {
                for (int x = 0; x < t[2]; x++) {
                    int pixel = from.getRGB(s[0] + x, s[1] + y);
                    if (opaque(pixel)) to.setRGB(t[0] + x, t[1] + y, pixel);
                }
            }
        }
    }

    /**
     * Centres each 2-pixel vanilla limb face in the wider player face and shades the rest from
     * the nearest bone pixel. {@code mirror} reproduces the vanilla mirrored left limbs, which the
     * player model draws from their own texture instead.
     */
    private static void thinLimb(BufferedImage from, Box source, BufferedImage to, Box target, boolean mirror) {
        int[][] sourceFaces = source.faces();
        int[][] targetFaces = target.faces();
        for (int face = 0; face < 6; face++) {
            int[] s = sourceFaces[mirror ? mirroredFace(face) : face];
            int[] t = targetFaces[face];
            int offsetX = (t[2] - s[2]) / 2;
            int offsetY = (t[3] - s[3]) / 2;
            for (int y = 0; y < t[3]; y++) {
                for (int x = 0; x < t[2]; x++) {
                    int sx = x - offsetX;
                    int sy = y - offsetY;
                    boolean inside = sx >= 0 && sx < s[2] && sy >= 0 && sy < s[3];
                    int cx = clamp(sx, s[2]);
                    if (mirror) cx = s[2] - 1 - cx;
                    int pixel = from.getRGB(s[0] + cx, s[1] + clamp(sy, s[3])) | 0xFF000000;
                    to.setRGB(t[0] + x, t[1] + y, inside ? pixel : shade(pixel, LIMB_SHADE));
                }
            }
        }
    }

    /** A mirrored box swaps its two side faces; every face is also flipped horizontally. */
    private static int mirroredFace(int face) {
        return face == 2 ? 4 : face == 4 ? 2 : face;
    }

    /** Dark warm grey, slightly lighter toward the top of the face, so gaps read as depth rather than holes. */
    private static int cavity(int y, int height) {
        int c = (int) (0x30 + 0x10 * (1.0 - (double) y / height));
        return 0xFF000000 | c << 16 | c << 8 | (c - 3);
    }

    private static int shade(int argb, double factor) {
        int r = (int) (((argb >> 16) & 0xFF) * factor);
        int g = (int) (((argb >> 8) & 0xFF) * factor);
        int b = (int) ((argb & 0xFF) * factor);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static int clamp(int value, int size) {
        return Math.max(0, Math.min(size - 1, value));
    }

    private static boolean opaque(int argb) {
        return argb >>> 24 == 0xFF;
    }

    /**
     * Every pixel the player model draws from the base layer must be painted on purpose: the client
     * would otherwise force it opaque and show its stored RGB, usually black.
     */
    static List<String> check(BufferedImage skin) {
        var problems = new ArrayList<String>();
        if (skin.getWidth() != 64 || skin.getHeight() != 64) problems.add("Skin must be 64x64");
        else for (Box box : BASE_BOXES) {
            for (int[] face : box.faces()) {
                for (int y = face[1]; y < face[1] + face[3]; y++) {
                    for (int x = face[0]; x < face[0] + face[2]; x++) {
                        int pixel = skin.getRGB(x, y);
                        int sum = ((pixel >> 16) & 0xFF) + ((pixel >> 8) & 0xFF) + (pixel & 0xFF);
                        if (!opaque(pixel)) problems.add("Transparent base pixel at " + x + "," + y);
                        else if (sum < BLACK_RGB_SUM) problems.add("Black base pixel at " + x + "," + y);
                    }
                }
            }
        }
        return problems;
    }

    /** Pixels that differ, treating any two fully transparent pixels as equal. */
    static int compare(BufferedImage expected, BufferedImage actual) {
        if (expected.getWidth() != actual.getWidth() || expected.getHeight() != actual.getHeight()) {
            return Integer.MAX_VALUE;
        }
        int differences = 0;
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                int a = expected.getRGB(x, y);
                int b = actual.getRGB(x, y);
                if (a != b && !(a >>> 24 == 0 && b >>> 24 == 0)) differences++;
            }
        }
        return differences;
    }

    private static BufferedImage read(Path file) throws IOException {
        BufferedImage image = ImageIO.read(file.toFile());
        if (image == null) throw new IOException("Not an image: " + file);
        return image;
    }
}
