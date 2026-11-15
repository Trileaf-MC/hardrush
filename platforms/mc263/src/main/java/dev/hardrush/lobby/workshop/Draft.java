package dev.hardrush.lobby.workshop;

import dev.hardrush.core.lobby.plan.Box;
import dev.hardrush.core.lobby.plan.Cell;
import dev.hardrush.core.lobby.plan.Point;
import dev.hardrush.world.LobbyDimension;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A workshop draft's metadata, {@code hardrush/workshop/<id>/draft.json} (design 11.1). Its blocks live in the
 * dimension {@code hardrush:workshop_<id>}. The build range is the dimension's vanilla world border: a square of
 * {@code borderDiameter} around {@code (borderCenterX, borderCenterZ)}.
 *
 * @param source          where the draft came from, for people ("当前大厅 默认大厅 v1", "空白")
 * @param participants    builders who entered at least once, in order of first entry
 * @param publishedVersion the last published version, 0 before the first publish
 * @param bottomY         blocks below it are left out when publishing (imported saves); null when unset
 * @param entry           where builders arrive: the first central landing marker the light check saw, else the
 *                        import's guess; null for {@link Workshop#DEFAULT_ENTRY}
 */
public record Draft(String id, String name, String source, List<Participant> participants, int publishedVersion,
                    int borderCenterX, int borderCenterZ, int borderDiameter, Integer bottomY, Point entry, State state) {
    /** {@code IMPORTING} until the draft's world is complete; such a draft cannot be entered. */
    public enum State { IMPORTING, READY }
    public record Participant(UUID id, String name) {
        public Participant { Objects.requireNonNull(id); Objects.requireNonNull(name); }
    }

    public Draft {
        if (!LobbyDimension.DRAFT_ID.matcher(id).matches()) throw new IllegalArgumentException("草稿 id 不合法：" + id);
        Objects.requireNonNull(name); Objects.requireNonNull(source); Objects.requireNonNull(state);
        participants = List.copyOf(participants);
        if (publishedVersion < 0) throw new IllegalArgumentException("发布版本不能为负");
        if (borderDiameter < 1) throw new IllegalArgumentException("建造范围直径至少为 1");
    }

    public static Draft create(String id, String name, String source, int diameter) {
        return new Draft(id, name, source, List.of(), 0, 0, 0, diameter, null, null, State.IMPORTING);
    }

    public Draft ready() { return with(participants, publishedVersion, borderCenterX, borderCenterZ, borderDiameter, bottomY, entry, State.READY); }
    public Draft withBorder(int centerX, int centerZ, int diameter) {
        return with(participants, publishedVersion, centerX, centerZ, diameter, bottomY, entry, state);
    }
    public Draft withBottomY(Integer y) { return with(participants, publishedVersion, borderCenterX, borderCenterZ, borderDiameter, y, entry, state); }
    public Draft withEntry(Point at) { return with(participants, publishedVersion, borderCenterX, borderCenterZ, borderDiameter, bottomY, at, state); }
    public Draft published(int version) { return with(participants, version, borderCenterX, borderCenterZ, borderDiameter, bottomY, entry, state); }

    /** Records a builder's first entry; a later entry under another name updates the name. */
    public Draft withParticipant(UUID player, String playerName) {
        var list = new ArrayList<Participant>();
        boolean seen = false;
        for (var p : participants) {
            if (p.id().equals(player)) { list.add(new Participant(player, playerName)); seen = true; }
            else list.add(p);
        }
        if (!seen) list.add(new Participant(player, playerName));
        var updated = with(list, publishedVersion, borderCenterX, borderCenterZ, borderDiameter, bottomY, entry, state);
        return updated.equals(this) ? this : updated;
    }

    /**
     * The build range between {@code minY} and {@code maxY}: the blocks the world border lets builders reach, which
     * is what publishing scans. An odd diameter leaves the extra block on the positive side.
     */
    public Box range(int minY, int maxY) {
        int minX = borderCenterX - borderDiameter / 2, minZ = borderCenterZ - borderDiameter / 2;
        return new Box(new Cell(minX, minY, minZ), new Cell(minX + borderDiameter - 1, maxY, minZ + borderDiameter - 1));
    }

    private Draft with(List<Participant> participants, int publishedVersion, int centerX, int centerZ, int diameter,
                       Integer bottomY, Point entry, State state) {
        return new Draft(id, name, source, participants, publishedVersion, centerX, centerZ, diameter, bottomY, entry, state);
    }
}
