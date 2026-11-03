# common-state

Versioned state for true-sim: know what changed without listeners. Package `com.truesim.common.state`.

Every change is stamped with a time from a `ChangeClock`; consumers remember the last time they
looked and compare. One `long` comparison per check, no listeners to register or forget, no
callbacks in the middle of an update, and ten changes in one frame cost one update. The idea comes
from Lemur's and SiO2's versioned objects, extended with a shared clock (one number covers many
objects, as in Bevy's change detection), per-element and per-key stamps with a change log for lists,
sets, maps and arrays, and lazily derived values.

## Usage

```kotlin
dependencies {
    implementation("com.truesim.common:common-state:0.2.0")
}
```

```java
ChangeClock clock = new ChangeClock();          // one per world; also the factory
VersionedDouble health = clock.doubleValue(100);
VersionedDouble maximum = clock.doubleValue(100);
VersionedList<Item> inventory = clock.list();
VersionedMap<PlayerId, Player> players = clock.map();

// Derived values recompute only when an input changed, and only when read.
Derived<String> label = Derived.of(() -> (int) health.get() + "/" + (int) maximum.get(), health, maximum);
DerivedDouble fraction = DerivedDouble.of(() -> health.get() / maximum.get(), health, maximum); // no boxing

// One object: a watch.
Watch labelWatch = label.watch();
// Many objects: a cursor, one number for all of them.
ChangeCursor cursor = clock.cursor();

// Every frame:
if (labelWatch.update()) {               // true the first time, then only after changes
    healthText.setText(label.get());
}
long since = cursor.mark();              // everything up to here was handled last frame
if (!inventory.changesSince(since, this::updateRow)) { // visits only the rows that changed
    rebuildRows();                       // first time, items added or removed, or the log overflowed
}
if (!players.changesSince(since, (id, change) -> {
    switch (change) {
        case ADDED, CHANGED -> showPlayer(id, players.get(id));
        case REMOVED -> hidePlayer(id);
    }
})) {
    rebuildPlayers();
}
```

## Types

| Type | Purpose |
|---|---|
| `ChangeClock` | Time source and factory; `stamp()` for your own versioned types |
| `VersionedValue<T>` | A value; an equal value (`equals`) is not a change; `touch()` after in-place edits |
| `VersionedDouble`, `VersionedInt`, `VersionedLong`, `VersionedBoolean` | Primitive values without boxing |
| `VersionedList<T>` | A `List` with per-element stamps (`version(i)`), a structure stamp (adds and removes) and `touch(i)` |
| `VersionedMap<K, V>`, `VersionedSet<T>` | Per-key stamps; `changesSince` reports keys `ADDED`, `CHANGED` or `REMOVED` |
| `VersionedDoubleArray`, `VersionedFloatArray`, `VersionedIntArray`, `VersionedLongArray` | Fixed-size entries with per-entry stamps, e.g. values a render buffer mirrors |
| `VersionedTree<T>` | Nodes with stable identity and dense ids; per-node value and children stamps, a structure stamp (adds, removes, moves) |
| `VersionedDoubleRing`, `VersionedFloatRing` | The latest samples of a stream in a fixed-capacity ring; `changesSince` reports the range appended |
| `Derived<T>`, `DerivedDouble`, `DerivedInt`, `DerivedLong`, `DerivedBoolean` | Lazily computed from inputs; versioned like their causing input change |
| `Watch` | A consumer's view of one object: `update()` is true once per change |
| `ChangeCursor` | A consumer's position on the clock: `mark()` returns the previous one |

## Change logs

Every collection keeps its recent changes in a ring (1024 by default, `clock.list(capacity)` and
friends to change it). `changesSince(since, visitor)` visits only what changed after `since`, each
index or key once, in the order of their latest changes, and returns `true`. It returns `false`
without visiting when the consumer should rebuild instead: the first time (`since` from before the
collection existed), after elements were added to or removed from a list (indices shift), after
`clear()` of a map or set, or when the consumer lagged behind by more changes than the ring holds.
Removed keys of maps and sets are remembered as long as their removal is in the ring, so memory
stays bounded.

## Trees and rings

A `VersionedTree` is the model behind a tree view. Nodes keep their identity while they move, and their
`id()` (the root's is 0, never reused) suits as the index of per-node arrays. A view compares
`structureVersion()` to know whether to sync rows at all, then each node's `childrenVersion()` to skip
unchanged branches; `changesSince` visits the nodes whose values changed, and keeps working across adds,
removes and moves. Removing a node detaches its subtree: still readable, but it refuses changes.

```java
VersionedTree<Item> tree = clock.tree(rootItem);
VersionedTree.Node<Item> folder = tree.root().add(folderItem);
folder.add(fileItem);
folder.move(tree.root(), 0);             // a change of structure, not of the value
```

A `VersionedDoubleRing` or `VersionedFloatRing` streams samples to a chart. Samples have absolute
indices from `first()` to `appended()`, which go on across wrap-around and `clear()`, so a chart copies
only what was appended since its last look:

```java
VersionedDoubleRing fps = clock.doubleRing(600);
fps.append(frameRate);                   // or append(values, from, to): one change per call
if (!fps.changesSince(since, (from, to) -> {
    fps.copy(from, to, buffer, 0);       // allocation-free, across the wrap
    chart.append(buffer, (int) (to - from));
})) {
    chart.reset(fps);                    // first time, after clear(), or the log overflowed
}
```

The primitive arrays, rings and derived values are generated from one template by
`tools/generate-primitives.sh`, so they behave alike.

## Rules

- Change state on one thread, e.g. the main thread. Results of background work arrive at a fixed
  point of the frame (common-jobs' `drainCompleted()`), so changes are stamped in a deterministic
  order.
- Setting an equal value is not a change; derived values whose result is equal do not change either.
- A derived value's version is the version of the input change that produced it: reading it never
  moves the clock, so lazy recomputation does not make cursors see a change twice.
- Checking, setting, `changesSince` and recomputing primitive derived values allocate nothing;
  `Derived<T>` allocates what its computation allocates, adding keys to maps and sets allocates
  their entries, and adding nodes to trees allocates the nodes.

## Building

```
./gradlew build
```

Uses `../build-logic` automatically when checked out next to this repository (override with
`-Ptruesim.build-logic=<path>`), or fetches it from GitHub Packages.
The build enforces platform independence, allocation-free code and 100% line and branch coverage.
