# common-state

Versioned state for true-sim: know what changed without listeners. Package `com.truesim.common.state`.

Every change is stamped with a time from a `ChangeClock`; consumers remember the last time they
looked and compare. One `long` comparison per check, no listeners to register or forget, no
callbacks in the middle of an update, and ten changes in one frame cost one update. The idea comes
from Lemur's and SiO2's versioned objects, extended with a shared clock (one number covers many
objects, as in Bevy's change detection), per-element stamps for lists and arrays, and lazily
derived values.

## Usage

```kotlin
dependencies {
    implementation("com.truesim.common:common-state:0.1.0")
}
```

```java
ChangeClock clock = new ChangeClock();          // one per world; also the factory
VersionedDouble health = clock.doubleValue(100);
VersionedDouble maximum = clock.doubleValue(100);
VersionedList<Item> inventory = clock.list();

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
if (inventory.structureVersion() > since) {
    rebuildRows();                       // items added or removed
} else {
    for (int i = 0; i < inventory.size(); i++) {
        if (inventory.version(i) > since) {
            updateRow(i);                // only the rows that changed
        }
    }
}
```

## Types

| Type | Purpose |
|---|---|
| `ChangeClock` | Time source and factory; `stamp()` for your own versioned types |
| `VersionedValue<T>` | A value; an equal value (`equals`) is not a change; `touch()` after in-place edits |
| `VersionedDouble`, `VersionedInt`, `VersionedLong`, `VersionedBoolean` | Primitive values without boxing |
| `VersionedList<T>` | A `List` with per-element stamps (`version(i)`), a structure stamp (adds and removes) and `touch(i)` |
| `VersionedDoubleArray` | Fixed-size doubles with per-entry stamps, e.g. values a render buffer mirrors |
| `Derived<T>`, `DerivedDouble` | Lazily computed from inputs; versioned like their causing input change |
| `Watch` | A consumer's view of one object: `update()` is true once per change |
| `ChangeCursor` | A consumer's position on the clock: `mark()` returns the previous one |

## Rules

- Change state on one thread, e.g. the main thread. Results of background work arrive at a fixed
  point of the frame (common-jobs' `drainCompleted()`), so changes are stamped in a deterministic
  order.
- Setting an equal value is not a change; derived values whose result is equal do not change either.
- A derived value's version is the version of the input change that produced it: reading it never
  moves the clock, so lazy recomputation does not make cursors see a change twice.
- Checking, setting and recomputing `DerivedDouble` allocate nothing; `Derived<T>` allocates what its
  computation allocates.

## Building

```
./gradlew build
```

To develop against a local build-logic checkout: `./gradlew build -Ptruesim.build-logic=../build-logic`.
The build enforces platform independence, allocation-free code and 100% line and branch coverage.
