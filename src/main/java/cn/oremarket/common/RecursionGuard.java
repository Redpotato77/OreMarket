/*
 * oremarket - Minecraft Forge Mod
 * Copyright (C) 2026 Redpotato77
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package cn.oremarket.common;

/**
 * Recursion depth guard (pure JDK, unit-testable): caps every entry point whose
 * translation/parsing pipeline might re-enter itself; on overflow the caller's terminal
 * branch runs (returning the raw text), eliminating StackOverflowError.
 *
 * Contract (safety red line):
 * - {@link #tryEnter()} must be paired with {@link #leave(int)} when it succeeds;
 * - {@code leave} restores the depth value saved BEFORE entry, not a decrement -
 * so the counter can never drift under exception paths or future re-entrance;
 * - the counter is ThreadLocal: the render thread / main thread / network callback threads
 * stay independent, with no cross-thread interference and no atomic contention.
 */
public final class RecursionGuard {

    private final int maxDepth;
    private final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);

    public RecursionGuard(int maxDepth) {
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth must be >= 1");
        }
        this.maxDepth = maxDepth;
    }

    /** Depth before entry (saved by the caller and restored via {@link #leave(int)}). */
    public int depth() {
        return depth.get();
    }

    /** Depth ceiling. */
    public int maxDepth() {
        return maxDepth;
    }

    /** Try to enter: false when the cap is hit (the caller must then take the terminal branch, never recurse further). */
    public boolean tryEnter() {
        int current = depth.get();
        if (current >= maxDepth) {
            return false;
        }
        depth.set(current + 1);
        return true;
    }

    /** Exit: restore the depth saved before entry (do not rewrite this as a decrement). */
    public void leave(int prevDepth) {
        depth.set(prevDepth);
    }
}
