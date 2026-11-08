package cn.oremarket.common;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Attack cases for the recursion guard: self-referencing templates, abnormal exits and concurrent threads must not drift the depth counter. */
final class RecursionGuardTest {

    @Test
    void capsSelfReferentialCycleAndRestoresDepth() {
        RecursionGuard guard = new RecursionGuard(5);
        int base = guard.depth();
        for (int i = 0; i < 5; i++) {
            assertTrue(guard.tryEnter());
        }
        assertFalse(guard.tryEnter(), "the sixth nesting level must be rejected");
        assertEquals(base + 5, guard.depth());
        guard.leave(base);
        assertEquals(base, guard.depth());
        assertTrue(guard.tryEnter(), "after restore the next entry must not be blocked by the stale depth");
        guard.leave(base);
    }

    @Test
    void finallyRestoresEntryValueWhenNestedCodeThrows() {
        RecursionGuard guard = new RecursionGuard(3);
        int outer = guard.depth();
        assertTrue(guard.tryEnter());
        int inner = guard.depth();
        try {
            assertTrue(guard.tryEnter());
            throw new IllegalStateException("attack input");
        } catch (IllegalStateException expected) {
            // Simulate a parse exception; finally must restore the value from before entering the inner level.
        } finally {
            guard.leave(inner);
        }
        assertEquals(inner, guard.depth());
        guard.leave(outer);
        assertEquals(outer, guard.depth());
    }

    @Test
    void depthIsIndependentBetweenThreads() throws Exception {
        RecursionGuard guard = new RecursionGuard(1);
        assertTrue(guard.tryEnter());
        AtomicInteger childDepth = new AtomicInteger(-1);
        Thread child = new Thread(() -> {
            childDepth.set(guard.depth());
            assertTrue(guard.tryEnter());
            guard.leave(0);
        });
        child.start();
        child.join();
        assertEquals(0, childDepth.get());
        guard.leave(0);
        assertEquals(0, guard.depth());
    }
}
