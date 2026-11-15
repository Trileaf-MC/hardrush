package dev.hardrush.lobby.workshop;

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
 */
public record Draft(String id, String name, String source, List<Participant> participants, int publishedVersion,
                    int borderCenterX, int borderCenterZ, int borderDiameter, Integer bottomY, State state) {
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
        return new Draft(id, name, source, List.of(), 0, 0, 0, diameter, null, State.IMPORTING);
    }

    public Draft ready() {
        return new Draft(id, name, source, participants, publishedVersion, borderCenterX, borderCenterZ, borderDiameter, bottomY, State.READY);
    }

    /** Records a builder's first entry; a later entry under another name updates the name. */
    public Draft withParticipant(UUID player, String playerName) {
        var list = new ArrayList<Participant>();
        boolean seen = false;
        for (var p : participants) {
            if (p.id().equals(player)) { list.add(new Participant(player, playerName)); seen = true; }
            else list.add(p);
        }
        if (!seen) list.add(new Participant(player, playerName));
        var updated = new Draft(id, name, source, list, publishedVersion, borderCenterX, borderCenterZ, borderDiameter, bottomY, state);
        return updated.equals(this) ? this : updated;
    }
}
