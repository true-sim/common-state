package com.truesim.common.state;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StateTest {

    private final ChangeClock clock = new ChangeClock();

    @Test
    void clockStampsInOrder() {
        assertThat(clock.now()).isZero();
        assertThat(clock.stamp()).isEqualTo(1);
        assertThat(clock.stamp()).isEqualTo(2);
        assertThat(clock.now()).isEqualTo(2);
    }

    @Test
    void anOwnedClockRefusesChangesFromOtherThreads() throws InterruptedException {
        VersionedDouble health = clock.doubleValue(100);
        assertThat(clock.owner()).isNull();
        clock.ownedBy(Thread.currentThread());
        health.set(90); // the owner
        Throwable[] thrown = new Throwable[1];
        Thread other = Thread.ofPlatform().name("worker").start(() -> {
            try {
                health.set(80);
            } catch (IllegalStateException e) {
                thrown[0] = e;
            }
        });
        other.join();
        assertThat(thrown[0]).hasMessageContaining("not on worker");
        assertThat(health.get()).isEqualTo(90);
        clock.ownedBy(null); // any thread again
        assertThat(clock.stamp()).isPositive();
    }

    @Test
    void valuesStampRealChangesOnly() {
        VersionedValue<String> name = clock.value("Ada");
        long created = name.version();
        assertThat(created).isEqualTo(clock.now());
        assertThat(name.set("Ada")).isFalse(); // equal: no change
        assertThat(name.version()).isEqualTo(created);
        assertThat(name.set("Grace")).isTrue();
        assertThat(name.get()).isEqualTo("Grace");
        assertThat(name.changedSince(created)).isTrue();
        assertThat(name.changedSince(name.version())).isFalse();
        name.touch();
        assertThat(name.version()).isEqualTo(clock.now());
        assertThat(name.toString()).isEqualTo("Grace");
        VersionedValue<String> empty = clock.value(null);
        assertThat(empty.set(null)).isFalse();
        assertThat(empty.toString()).isEqualTo("null");
    }

    @Test
    void primitiveValues() {
        VersionedDouble d = clock.doubleValue(Double.NaN);
        assertThat(d.set(Double.NaN)).isFalse(); // NaN equals NaN bit for bit
        assertThat(d.set(0.0)).isTrue();
        assertThat(d.set(-0.0)).isTrue(); // different bits
        assertThat(d.get()).isEqualTo(-0.0);
        assertThat(d.toString()).isEqualTo("-0.0");
        VersionedInt i = clock.intValue(3);
        assertThat(i.set(3)).isFalse();
        assertThat(i.set(4)).isTrue();
        assertThat(i.get()).isEqualTo(4);
        assertThat(i.toString()).isEqualTo("4");
        VersionedLong l = clock.longValue(5);
        assertThat(l.set(5)).isFalse();
        assertThat(l.set(6)).isTrue();
        assertThat(l.get()).isEqualTo(6);
        assertThat(l.toString()).isEqualTo("6");
        VersionedBoolean b = clock.booleanValue(false);
        assertThat(b.set(false)).isFalse();
        assertThat(b.set(true)).isTrue();
        assertThat(b.get()).isTrue();
        assertThat(b.toString()).isEqualTo("true");
        assertThat(b.version()).isGreaterThan(l.version()).isGreaterThan(i.version()).isGreaterThan(d.version());
    }

    @Test
    void watchesSeeEachChangeOnce() {
        VersionedInt score = clock.intValue(0);
        Watch watch = score.watch();
        assertThat(watch.target()).isSameAs(score);
        assertThat(watch.isChanged()).isTrue();
        assertThat(watch.update()).isTrue(); // first time: build the initial view
        assertThat(watch.update()).isFalse();
        score.set(1);
        score.set(2); // two changes in one frame: one update
        assertThat(watch.isChanged()).isTrue();
        assertThat(watch.update()).isTrue();
        assertThat(watch.update()).isFalse();
        assertThat(watch.isChanged()).isFalse();
    }

    @Test
    void cursorsCoverManyObjectsWithOneNumber() {
        VersionedInt a = clock.intValue(0);
        VersionedInt b = clock.intValue(0);
        ChangeCursor cursor = clock.cursor();
        assertThat(cursor.lastMark()).isEqualTo(-1);
        long since = cursor.mark();
        assertThat(since).isEqualTo(-1);
        assertThat(a.changedSince(since)).isTrue();
        assertThat(b.changedSince(since)).isTrue();
        since = cursor.mark();
        assertThat(a.changedSince(since)).isFalse();
        b.set(1);
        since = cursor.mark();
        assertThat(a.changedSince(since)).isFalse();
        assertThat(b.changedSince(since)).isTrue();
        assertThat(cursor.lastMark()).isEqualTo(clock.now());
    }

    @Test
    void listsTrackElementsAndStructure() {
        VersionedList<String> items = clock.list();
        long since = clock.now(); // an explicit baseline; a frame loop would use cursor.mark()
        for (int i = 0; i < 20; i++) {
            items.add("item " + i); // grows past the initial capacity
        }
        assertThat(items).hasSize(20).startsWith("item 0", "item 1");
        assertThat(items.structureVersion()).isGreaterThan(since);
        since = clock.now();
        // Element changes leave the structure alone.
        assertThat(items.set(3, "item 3")).isEqualTo("item 3"); // equal: no change
        assertThat(items.version(3)).isLessThanOrEqualTo(since);
        assertThat(items.set(3, "sword")).isEqualTo("item 3");
        items.touch(7);
        assertThat(items.structureVersion()).isLessThanOrEqualTo(since);
        List<Integer> changed = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (items.version(i) > since) {
                changed.add(i);
            }
        }
        assertThat(changed).containsExactly(3, 7);
        assertThat(items.version()).isEqualTo(items.version(7));
        // Inserting and removing shift elements together with their stamps.
        since = clock.now();
        long stampOf7 = items.version(7);
        items.add(0, "first");
        assertThat(items.version(8)).isEqualTo(stampOf7);
        assertThat(items.remove(0)).isEqualTo("first");
        assertThat(items.version(7)).isEqualTo(stampOf7);
        assertThat(items.structureVersion()).isGreaterThan(since);
        items.subList(5, 10).clear();
        assertThat(items).hasSize(15).contains("item 4", "item 10").doesNotContain("item 5", "item 9");
        items.clear();
        assertThat(items).isEmpty();
        assertThat(items.version()).isEqualTo(items.structureVersion());
        // Still a well-behaved List.
        assertThatThrownBy(() -> items.get(0)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> items.add(1, "x")).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> items.touch(0)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> items.version(0)).isInstanceOf(IndexOutOfBoundsException.class);
        items.add("a");
        Iterator<String> iterator = items.iterator();
        items.add("b");
        assertThatThrownBy(iterator::next).isInstanceOf(ConcurrentModificationException.class);
        assertThat(items.set(0, null)).isEqualTo("a");
        assertThat(items.get(0)).isNull();
    }

    @Test
    void doubleArrays() {
        VersionedDoubleArray hp = clock.doubleArray(4);
        assertThat(hp.length()).isEqualTo(4);
        long created = hp.version();
        assertThat(hp.version(2)).isEqualTo(created);
        assertThat(hp.set(2, 0)).isFalse();
        assertThat(hp.set(2, 75)).isTrue();
        assertThat(hp.get(2)).isEqualTo(75);
        assertThat(hp.version(2)).isGreaterThan(created);
        assertThat(hp.version(1)).isEqualTo(created);
        assertThat(hp.version()).isEqualTo(hp.version(2));
        assertThatThrownBy(() -> hp.set(4, 1)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void derivedValuesRecomputeLazilyAndOnlyWhenNeeded() {
        VersionedDouble health = clock.doubleValue(80);
        VersionedDouble maximum = clock.doubleValue(100);
        AtomicInteger computations = new AtomicInteger();
        Derived<String> label = Derived.of(() -> {
            computations.incrementAndGet();
            return (int) health.get() + "/" + (int) maximum.get();
        }, health, maximum);
        assertThat(computations.get()).isZero(); // nothing until read
        assertThat(label.get()).isEqualTo("80/100");
        assertThat(label.get()).isEqualTo("80/100");
        assertThat(computations.get()).isEqualTo(1);
        // Its version is the input change that produced it.
        assertThat(label.version()).isEqualTo(maximum.version());
        health.set(80.4); // same label: recomputed, but not a change
        assertThat(label.get()).isEqualTo("80/100");
        assertThat(computations.get()).isEqualTo(2);
        assertThat(label.version()).isEqualTo(maximum.version());
        health.set(50);
        assertThat(label.version()).isEqualTo(health.version());
        assertThat(label.get()).isEqualTo("50/100");
        // Derived values feed derived values, and work with watches and cursors.
        Derived<Integer> length = Derived.of(() -> label.get().length(), label);
        Watch watch = length.watch();
        assertThat(watch.update()).isTrue();
        assertThat(length.get()).isEqualTo(6);
        maximum.set(150);
        assertThat(watch.update()).isFalse(); // "50/150": same length
        health.set(5);
        assertThat(watch.update()).isTrue();
        assertThat(length.get()).isEqualTo(5);
        // The primitive variant: same rules, no boxing.
        DerivedDouble fraction = DerivedDouble.of(() -> health.get() / maximum.get(), health, maximum);
        assertThat(fraction.get()).isEqualTo(5 / 150.0);
        assertThat(fraction.version()).isEqualTo(health.version());
        maximum.set(150); // not a change
        health.set(5.0);
        assertThat(fraction.get()).isEqualTo(5 / 150.0);
        maximum.set(300);
        assertThat(fraction.get()).isEqualTo(5 / 300.0);
        assertThat(fraction.version()).isEqualTo(maximum.version());
        DerivedDouble unchanged = DerivedDouble.of(() -> Math.floor(health.get()), health);
        assertThat(unchanged.get()).isEqualTo(5);
        long before = unchanged.version();
        health.set(5.5); // recomputed, same result: not a change
        assertThat(unchanged.get()).isEqualTo(5);
        assertThat(unchanged.version()).isEqualTo(before);
        assertThatNullPointerException().isThrownBy(() -> DerivedDouble.of(null, health));
        // A constant: computed once.
        Derived<String> constant = Derived.of(() -> "fixed");
        assertThat(constant.get()).isEqualTo("fixed");
        assertThat(constant.version()).isZero();
        assertThatNullPointerException().isThrownBy(() -> Derived.of(null, health));
    }

    private double sink;

    @Test
    void checkingAndSettingAllocateNothing() {
        VersionedDouble health = clock.doubleValue(0);
        VersionedDoubleArray hp = clock.doubleArray(64);
        VersionedList<String> items = clock.list();
        items.add("a");
        Watch watch = health.watch();
        ChangeCursor cursor = clock.cursor();
        DerivedDouble half = DerivedDouble.of(() -> health.get() / 2, health); // no boxing: recomputes allocation-free
        int[] i = {0};
        assertAllocationFree(() -> {
            int k = i[0] = (i[0] + 1) & 63;
            health.set(k);
            hp.set(k, k * 0.5);
            items.touch(0);
            long since = cursor.mark();
            sink += (watch.update() ? 1 : 0) + (hp.changedSince(since) ? 1 : 0) + items.version(0) + half.version();
        });
    }
}
