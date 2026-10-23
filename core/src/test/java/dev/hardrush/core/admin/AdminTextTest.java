package dev.hardrush.core.admin;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AdminTextTest {
    @Test void reasonAndActorRejectUnboundedOrUnsafeTextWithoutSilentlyChangingIt() {
        assertEquals("原样：管理员中止", AdminText.reason("原样：管理员中止"));
        assertEquals("😀".repeat(512), AdminText.reason("😀".repeat(512)));
        for (String unsafe : new String[] {"", " ", "line\nbreak", "§c改变样式", "\u202E隐藏方向", "\uD800", "a".repeat(513)}) {
            assertThrows(IllegalArgumentException.class, () -> AdminText.reason(unsafe));
        }
        assertThrows(IllegalArgumentException.class, () -> new AdminActor("操作者\t", "Name"));
    }
}
