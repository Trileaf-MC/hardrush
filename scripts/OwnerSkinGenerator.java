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
 * Builds owner-only player skins for monster forms from the vanilla mob textures: the skeleton and its bogged
 * and parched variants, the husk, the zombified piglin, and the charged creeper a creeper player sees itself as
 * once it carries a lightning charge.
 *
 * <p>The vanilla client forces the player's base skin layer opaque
 * ({@code SkinTextureDownloader.processLegacySkin -> setNoAlpha}); transparent pixels keep
 * their RGB, which is normally black. A skeleton's see-through ribs and thin limbs therefore
 * cannot exist on the player model. Instead, every base pixel is painted on purpose: vanilla
 * bones stay as they are, the gaps between them become a dark "body cavity", and the 2-pixel
 * vanilla limbs sit in the middle of the 4-pixel player limbs with shaded sides, so they read
 * as thin bones. Ribs and spine are repeated on the jacket layer for relief in third person.
 *
 * <p>The charged creeper keeps the creeper's own mottled hide on the base layer (the creeper has no arms and
 * short legs, so the limbs continue the body's pattern and end in the creeper's feet) and paints the brighter
 * streaks of the charge texture on the jacket, sleeves and trousers. Those outer layers stand off the body
 * like the native charge layer does, and the sleeve is what the owner sees in first person. The head's outer
 * layer stays clear so the face reads as a plain creeper.
 *
 * <p>A bogged or parched is the skeleton built the same way from its own bones, with its moss or rags repeated on
 * the outer layers. A husk's texture already is a player's but for the left limbs, which the zombie model mirrors
 * from the right ones. A zombified piglin's head is ten pixels wide: the player's eight keep its middle, and the
 * snout's face goes on the hat layer, which stands off the head as the snout does.
 *
 * <p>The vanilla texture is read from the local client JAR and the result is written under
 * {@code .local/}; derived Mojang art is not committed. The uploaded result is pinned in
 * {@code platforms/mc263/src/main/resources/hardrush/monster-skins.json}.
 *
 * <pre>
 * java scripts/OwnerSkinGenerator.java generate [--client-jar JAR] [--out DIR]
 * java scripts/OwnerSkinGenerator.java check SKIN.png
 * java scripts/OwnerSkinGenerator.java compare EXPECTED.png ACTUAL.png
 * </pre>
 *
 * <p>Publishing: upload {@code DIR/skeleton.png} or {@code DIR/creeper_charged.png} as a classic-model skin, for example
 * {@code curl -F file=@skeleton.png -F variant=classic -F visibility=unlisted
 * https://api.mineskin.org/v2/generate}, download the returned textures.minecraft.net URL and
 * {@code compare} it with the generated file before pinning the property value and its
 * signature: other players' clients ignore an unsigned skin, so a TAB head would fall back to
 * the default skin.
 */
public final class OwnerSkinGenerator {
    private static final String VANILLA_SKELETON = "assets/minecraft/textures/entity/skeleton/skeleton.png";
    private static final String VANILLA_CREEPER = "assets/minecraft/textures/entity/creeper/creeper.png";
    private static final String VANILLA_CHARGE = "assets/minecraft/textures/entity/creeper/creeper_armor.png";
    private static final String VANILLA_BOGGED = "assets/minecraft/textures/entity/skeleton/bogged.png";
    private static final String VANILLA_BOGGED_OVERLAY = "assets/minecraft/textures/entity/skeleton/bogged_overlay.png";
    private static final String VANILLA_PARCHED = "assets/minecraft/textures/entity/skeleton/parched.png";
    private static final String VANILLA_HUSK = "assets/minecraft/textures/entity/zombie/husk.png";
    private static final String VANILLA_ZOMBIFIED_PIGLIN = "assets/minecraft/textures/entity/piglin/zombified_piglin.png";
    /** Green level of the charge texture's darkest streak that is still drawn, and of one drawn at full strength. */
    private static final int CHARGE_FAINT = 58, CHARGE_FULL = 200;
    /** How far a drawn streak is lifted toward white; the native layer is additive, a skin layer is not. */
    private static final double CHARGE_LIFT = 0.35;
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
    private static final Box RIGHT_SLEEVE = new Box(40, 32, 4, 12, 4);
    private static final Box LEFT_SLEEVE = new Box(48, 48, 4, 12, 4);
    private static final Box RIGHT_TROUSER = new Box(0, 32, 4, 12, 4);
    private static final Box LEFT_TROUSER = new Box(0, 48, 4, 12, 4);

    // Vanilla creeper (64x32): head and body match the player boxes; the four legs are 4x6x4.
    private static final Box CREEPER_HEAD = new Box(0, 0, 8, 8, 8);
    private static final Box CREEPER_BODY = new Box(16, 16, 8, 12, 4);
    private static final Box CREEPER_LEG = new Box(0, 16, 4, 6, 4);
    private static final Box HAT = new Box(32, 0, 8, 8, 8);
    // Vanilla piglin (64x64): a 10x8x8 head with a 4x4x1 snout; body and limbs are the player's.
    private static final Box PIGLIN_HEAD = new Box(0, 0, 10, 8, 8);
    private static final Box PIGLIN_SNOUT = new Box(31, 1, 4, 4, 1);
    private static final List<Box> BASE_BOXES = List.of(HEAD, BODY, RIGHT_ARM, LEFT_ARM, RIGHT_LEG, LEFT_LEG);

    public static void main(String[] args) throws IOException {
        if (args.length == 0) throw usage();
        switch (args[0]) {
            case "generate" -> generate(args);
            case "check" -> {
                if (args.length != 2) throw usage();
                List<String> problems = check(read(Path.of(args[1])), true);
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
        Files.createDirectories(out);
        try (var zip = new ZipFile(jar.toFile())) {
            write(out.resolve("skeleton.png"), skeleton(texture(zip, VANILLA_SKELETON)), true);
            write(out.resolve("creeper_charged.png"),
                chargedCreeper(texture(zip, VANILLA_CREEPER), texture(zip, VANILLA_CHARGE)), false);
            write(out.resolve("bogged.png"), dressedSkeleton(texture(zip, VANILLA_BOGGED), texture(zip, VANILLA_BOGGED_OVERLAY)), false);
            // The parched keeps its rags in the lower half of its own 64x64 texture, laid out like the upper.
            BufferedImage parched = texture(zip, VANILLA_PARCHED);
            write(out.resolve("parched.png"), dressedSkeleton(parched.getSubimage(0, 0, 64, 32), parched.getSubimage(0, 32, 64, 32)), false);
            write(out.resolve("husk.png"), husk(texture(zip, VANILLA_HUSK)), false);
            write(out.resolve("zombified_piglin.png"), zombifiedPiglin(texture(zip, VANILLA_ZOMBIFIED_PIGLIN)), false);
        }
        System.out.println("wrote skeleton, creeper_charged, bogged, parched, husk and zombified_piglin skins to " + out + " from " + jar);
    }

    private static BufferedImage texture(ZipFile zip, String name) throws IOException {
        var entry = zip.getEntry(name);
        if (entry == null) throw new IllegalStateException(name + " not found in " + zip.getName());
        try (var input = zip.getInputStream(entry)) {
            // Indexed vanilla textures are read through their palette.
            BufferedImage image = ImageIO.read(input);
            var argb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) argb.setRGB(x, y, image.getRGB(x, y));
            return argb;
        }
    }

    private static void write(Path file, BufferedImage skin, boolean noBlack) throws IOException {
        List<String> problems = check(skin, noBlack);
        if (!problems.isEmpty()) throw new IllegalStateException(file.getFileName() + ":\n" + String.join("\n", problems));
        ImageIO.write(skin, "png", file.toFile());
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

    /** A skeleton variant: its own bones as {@link #skeleton}, and what it wears on the outer layers. */
    static BufferedImage dressedSkeleton(BufferedImage bones, BufferedImage worn) {
        BufferedImage skin = skeleton(bones);
        if (worn.getWidth() != 64 || worn.getHeight() != 32) throw new IllegalStateException("Expected a 64x32 skeleton overlay");
        wear(worn, SKELETON_HEAD, skin, HAT, false);
        wear(worn, SKELETON_BODY, skin, JACKET, false);
        wear(worn, SKELETON_ARM, skin, RIGHT_SLEEVE, false);
        wear(worn, SKELETON_ARM, skin, LEFT_SLEEVE, true);
        wear(worn, SKELETON_LEG, skin, RIGHT_TROUSER, false);
        wear(worn, SKELETON_LEG, skin, LEFT_TROUSER, true);
        return skin;
    }

    /** The painted pixels of an overlay box, centred on the player's outer box; what it leaves clear stays as it is. */
    private static void wear(BufferedImage from, Box source, BufferedImage to, Box target, boolean mirror) {
        int[][] sourceFaces = source.faces();
        int[][] targetFaces = target.faces();
        for (int face = 0; face < 6; face++) {
            int[] s = sourceFaces[mirror ? mirroredFace(face) : face];
            int[] t = targetFaces[face];
            int offsetX = (t[2] - s[2]) / 2;
            int offsetY = (t[3] - s[3]) / 2;
            for (int y = 0; y < s[3]; y++) {
                for (int x = 0; x < s[2]; x++) {
                    int pixel = from.getRGB(s[0] + (mirror ? s[2] - 1 - x : x), s[1] + y);
                    if (opaque(pixel)) to.setRGB(t[0] + offsetX + x, t[1] + offsetY + y, pixel);
                }
            }
        }
    }

    /** The zombie model draws its left limbs mirrored from the right ones; a player skin carries them itself. */
    static BufferedImage husk(BufferedImage vanilla) {
        if (vanilla.getWidth() != 64 || vanilla.getHeight() != 64) throw new IllegalStateException("Expected the 64x64 vanilla husk texture");
        var skin = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (Box box : List.of(HEAD, BODY, RIGHT_ARM, RIGHT_LEG)) copyFilled(vanilla, box, skin, box);
        mirrored(vanilla, RIGHT_ARM, skin, LEFT_ARM);
        mirrored(vanilla, RIGHT_LEG, skin, LEFT_LEG);
        return skin;
    }

    private static void mirrored(BufferedImage from, Box source, BufferedImage to, Box target) {
        int[][] sourceFaces = source.faces();
        int[][] targetFaces = target.faces();
        for (int face = 0; face < 6; face++) {
            int[] s = sourceFaces[mirroredFace(face)];
            int[] t = targetFaces[face];
            for (int y = 0; y < t[3]; y++) {
                for (int x = 0; x < t[2]; x++) {
                    int pixel = from.getRGB(s[0] + s[2] - 1 - x, s[1] + y);
                    to.setRGB(t[0] + x, t[1] + y, opaque(pixel) ? pixel : cavity(y, t[3]));
                }
            }
        }
    }

    static BufferedImage zombifiedPiglin(BufferedImage vanilla) {
        if (vanilla.getWidth() != 64 || vanilla.getHeight() != 64) throw new IllegalStateException("Expected the 64x64 vanilla zombified piglin texture");
        var skin = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        int[][] wide = PIGLIN_HEAD.faces();
        int[][] head = HEAD.faces();
        for (int face = 0; face < 6; face++) {
            // The two side faces are the player's width; the others lose a column at each edge.
            int skip = (wide[face][2] - head[face][2]) / 2;
            for (int y = 0; y < head[face][3]; y++) {
                for (int x = 0; x < head[face][2]; x++) {
                    int pixel = vanilla.getRGB(wide[face][0] + skip + x, wide[face][1] + y);
                    skin.setRGB(head[face][0] + x, head[face][1] + y, opaque(pixel) ? pixel : cavity(y, head[face][3]));
                }
            }
        }
        int[] snout = PIGLIN_SNOUT.faces()[3];
        int[] hatFront = HAT.faces()[3];
        for (int y = 0; y < snout[3]; y++) {
            for (int x = 0; x < snout[2]; x++) {
                int pixel = vanilla.getRGB(snout[0] + x, snout[1] + y);
                if (opaque(pixel)) skin.setRGB(hatFront[0] + 2 + x, hatFront[1] + 4 + y, pixel);
            }
        }
        for (Box box : List.of(BODY, RIGHT_ARM, LEFT_ARM, RIGHT_LEG, LEFT_LEG)) copyFilled(vanilla, box, skin, box);
        for (Box box : List.of(JACKET, RIGHT_SLEEVE, LEFT_SLEEVE, RIGHT_TROUSER, LEFT_TROUSER)) wear(vanilla, box, skin, box, false);
        return skin;
    }

    static BufferedImage chargedCreeper(BufferedImage creeper, BufferedImage charge) {
        if (creeper.getWidth() != 64 || creeper.getHeight() != 32 || charge.getWidth() != 64 || charge.getHeight() != 32) {
            throw new IllegalStateException("Expected the 64x32 vanilla creeper and charge textures");
        }
        var skin = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        copyFilled(creeper, CREEPER_HEAD, skin, HEAD);
        copyFilled(creeper, CREEPER_BODY, skin, BODY);
        creeperLimb(creeper, skin, RIGHT_ARM, 0, 0, false);
        creeperLimb(creeper, skin, LEFT_ARM, 4, 0, false);
        creeperLimb(creeper, skin, RIGHT_LEG, 0, 6, true);
        creeperLimb(creeper, skin, LEFT_LEG, 4, 6, true);
        for (Box layer : List.of(JACKET, RIGHT_SLEEVE, LEFT_SLEEVE, RIGHT_TROUSER, LEFT_TROUSER)) charge(charge, skin, layer);
        return skin;
    }

    /**
     * A player limb in the creeper's hide. Its long faces continue the body's pattern, taken {@code column} pixels
     * into the body's wide faces and {@code row} pixels down; a leg ends in the creeper's own foot, and both ends
     * of a limb are the creeper's leg ends.
     */
    private static void creeperLimb(BufferedImage creeper, BufferedImage skin, Box target, int column, int row, boolean foot) {
        int[][] body = CREEPER_BODY.faces();
        int[][] leg = CREEPER_LEG.faces();
        int[][] faces = target.faces();
        for (int face = 0; face < 6; face++) {
            int[] t = faces[face];
            for (int y = 0; y < t[3]; y++) {
                for (int x = 0; x < t[2]; x++) {
                    int pixel;
                    if (face < 2) pixel = creeper.getRGB(leg[face][0] + x, leg[face][1] + y);
                    else if (foot && y >= t[3] - CREEPER_LEG.h()) pixel = creeper.getRGB(leg[face][0] + x, leg[face][1] + y - (t[3] - CREEPER_LEG.h()));
                    else pixel = creeper.getRGB(body[face][0] + (body[face][2] > t[2] ? column : 0) + x, body[face][1] + (row + y) % CREEPER_BODY.h());
                    skin.setRGB(t[0] + x, t[1] + y, pixel | 0xFF000000);
                }
            }
        }
    }

    /** The charge texture tiles, so an outer box takes the streaks that lie under its own place in the skin. */
    private static void charge(BufferedImage charge, BufferedImage skin, Box layer) {
        for (int[] face : layer.faces()) {
            for (int y = face[1]; y < face[1] + face[3]; y++) {
                for (int x = face[0]; x < face[0] + face[2]; x++) {
                    int pixel = charge.getRGB(x % charge.getWidth(), y % charge.getHeight());
                    int green = (pixel >> 8) & 0xFF;
                    if (!opaque(pixel) || green < CHARGE_FAINT) continue;
                    int alpha = (int) (110 + 145 * Math.min(1.0, (double) (green - CHARGE_FAINT) / (CHARGE_FULL - CHARGE_FAINT)));
                    skin.setRGB(x, y, alpha << 24 | lift((pixel >> 16) & 0xFF) << 16 | lift(green) << 8 | lift(pixel & 0xFF));
                }
            }
        }
    }

    private static int lift(int channel) {
        return (int) (channel + (255 - channel) * CHARGE_LIFT);
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
                    int pixel = from.getRGB(s[0] + cx, s[1] + clamp(sy, s[3]));
                    // A variant leaves some limb ends unpainted; forced opaque they would show as black.
                    if (!opaque(pixel)) pixel = cavity(y, t[3]);
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
     * would otherwise force it opaque and show its stored RGB, usually black. {@code noBlack} also rejects black
     * pixels, for a skin whose source has none; a creeper's face is black on purpose.
     */
    static List<String> check(BufferedImage skin, boolean noBlack) {
        var problems = new ArrayList<String>();
        if (skin.getWidth() != 64 || skin.getHeight() != 64) problems.add("Skin must be 64x64");
        else for (Box box : BASE_BOXES) {
            for (int[] face : box.faces()) {
                for (int y = face[1]; y < face[1] + face[3]; y++) {
                    for (int x = face[0]; x < face[0] + face[2]; x++) {
                        int pixel = skin.getRGB(x, y);
                        int sum = ((pixel >> 16) & 0xFF) + ((pixel >> 8) & 0xFF) + (pixel & 0xFF);
                        if (!opaque(pixel)) problems.add("Transparent base pixel at " + x + "," + y);
                        else if (noBlack && sum < BLACK_RGB_SUM) problems.add("Black base pixel at " + x + "," + y);
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
