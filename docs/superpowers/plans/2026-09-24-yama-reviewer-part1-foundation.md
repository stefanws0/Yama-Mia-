# Yama Reviewer Part 1: Foundation and Capture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A RuneLite plugin that silently records every Yama kill as an append-only event stream on disk, so the user can do the logging kills that later parts are confirmed against.

**Architecture:** Hexagonal layers under `com.yamareviewer`: `domain` (events, value objects, ID registry, tunables), `application` (the `KillSession` write side, ports, the kill-ended handler), `adapter` (`gameval` IDs, RuneLite recording, `Filepath` persistence) and a thin root-package plugin class that only wires objects. `GameEventListener` is the only class that receives RuneLite game events. During a fight only raw observations are appended; when the kill ends, the frozen `KillLog` is saved on the plugin's own executor.

**Tech Stack:** Java 11 (release target), Gradle from the RuneLite example-plugin template, RuneLite client API (`latest.release`), Lombok, Gson (RuneLite's injected instance), JUnit 4.12, Mockito 5, ArchUnit 1.3.

**Spec:** `docs/superpowers/specs/2026-09-24-yama-reviewer-design.md`, sections 2, 4, 5, 12 and 13 (part 1).

## Global Constraints

- Build matches the RuneLite example-plugin template; `options.release.set(11)`.
- Root package `com.yamareviewer`; `@PluginDescriptor(internalName = "yama-reviewer")`; config group `yamareviewer`.
- Nothing is drawn, printed or played during a fight. Part 1 has no overlay, panel, chat message, notification or sound at all.
- Record only inside region 6045 (role `YAMAS_DOMAIN`).
- All file I/O through RuneLite's `Filepath` via `Plugin.getPluginDirectory()`; never `java.io.File` or `java.nio.file.Files` in main code; `Filepath.Unchecked` only in tests.
- Use the injected `Gson` in main code; tests may use `new Gson()`.
- No reflection in main code. `log.debug` for per-event logging.
- `domain` imports nothing from `application`, `adapter`, `net.runelite`, `com.google`, Swing or AWT. `application` imports nothing from `adapter`, `net.runelite`, `com.google`, Swing or AWT.
- Only `adapter.recording` imports `net.runelite.api.events` or declares `@Subscribe` methods.
- Yama-specific game IDs appear only in `adapter.ids.BuiltInIds`, as `gameval` constants (region 6045 and overhead texts are literals there). General client state (prayer varbits, inventory containers, spec energy, rune pouch) uses `gameval` constants where it is read.
- Event type names in the log (`EventType`) never change once released.
- RuneLite code style: tabs, braces on their own line.
- Building needs network access to `https://repo.runelite.net` and Maven Central.

## Review Focus

1. Logging out, hopping or disabling the plugin mid-fight ends the kill as `LEFT` at once, without an END snapshot from an empty inventory (KillSessionTest `leavingMidFight…`, `endSnapshotIsSkippedWhenUnavailable`; GameEventListenerTest `loggingOutEndsTheFightAsLeft`; SnapshotReaderTest `notLoggedInGivesNoSnapshot`).
2. A death is completed on the next tick so the death tick's `TickState` is in the log, and a logout right after a death keeps the death as the reason (KillSessionTest `deathKeepsTheDeathTicksState`, `leaveAfterDeathKeepsTheDeathReason`; GameEventListenerTest `deathEndsOnTheNextTick…`).
3. Yama despawning on a scene reload is not a kill, and his spawn repeating while fighting doesn't restart it (GameEventListenerTest `yamaDespawningOnASceneReloadIsNotAKill`; KillSessionTest `spawnWhileFightingIsIgnored`).
4. A duo partner who loads in after Yama appeared still becomes the partner; the local player never does (GameEventListenerTest `aPartnerLoadingInLateIsSeen`, `theLocalPlayerIsNeverAPartner`).
5. Corrupt files and logs of another schema version in `raw/` are skipped while the others load (GsonLogRepositoryTest `corruptFileIsRenamedAndSkipped`, `otherSchemaVersionsAreSkipped`).

---

## File Structure

```
build.gradle, settings.gradle, runelite-plugin.properties, gradlew, gradlew.bat, gradle/, .gitignore, LICENSE, README.md
src/main/java/com/yamareviewer/
  YamaReviewerPlugin.java          composition root: builds objects, registers the listener
  YamaReviewerConfig.java          config (Part 1 keys: rawLogsKept, captureMode)
  domain/event/                    Actor, value types, DomainEvent, EventType and 24 event classes
  domain/model/                    KillHeader, KillLog
  domain/ids/                      Role, RoleKind, PrayerCheck, Rules, IdRegistry
  application/port/                LogRepository, SnapshotSource
  application/command/             KillSession, SessionState, FightStart, KillEndedListener
  application/handler/             KillEndedHandler
  adapter/ids/                     BuiltInIds
  adapter/persistence/             FileStore, FilepathFileStore, EventCodec, GsonLogRepository
  adapter/recording/               PositionReader, ActorResolver, EventTranslator, ItemLookup, ItemManagerLookup,
                                   TickSampler, SnapshotReader, GameEventListener
src/test/java/com/yamareviewer/
  YamaReviewerPluginTest.java      dev-client launcher (template convention, not a unit test)
  ArchitectureTest.java
  domain/…, application/…, adapter/… tests mirroring main
  tools/CaptureSummary.java, tools/GamevalNames.java
docs/logging-kills.md
```

---

### Task 1: Project scaffold

**Files:**
- Create: `build.gradle`, `settings.gradle`, `runelite-plugin.properties`, `LICENSE`, `README.md`
- Copy from template: `gradlew`, `gradlew.bat`, `gradle/wrapper/*`, `.gitignore`, `src/test/resources/logback-test.xml`
- Create: `src/main/java/com/yamareviewer/YamaReviewerPlugin.java`, `src/main/java/com/yamareviewer/YamaReviewerConfig.java`
- Create: `src/test/java/com/yamareviewer/YamaReviewerPluginTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `YamaReviewerConfig` with `int rawLogsKept()` (default 20) and `boolean captureMode()` (default false); `YamaReviewerConfig.GROUP = "yamareviewer"`.

- [ ] **Step 1: Copy the template's build tooling**

```bash
git clone --depth 1 https://github.com/runelite/example-plugin.git /tmp/example-plugin
cp -r /tmp/example-plugin/gradlew /tmp/example-plugin/gradlew.bat /tmp/example-plugin/gradle /tmp/example-plugin/.gitignore .
mkdir -p src/test/resources
cp /tmp/example-plugin/src/test/resources/logback-test.xml src/test/resources/
```

- [ ] **Step 2: Write `build.gradle`**

```groovy
plugins {
	id 'java'
}

repositories {
	mavenLocal()
	maven {
		url = 'https://repo.runelite.net'
		content {
			includeGroupByRegex("net\\.runelite.*")
		}
	}
	mavenCentral()
}

def runeLiteVersion = 'latest.release'
def pluginMainClass = 'com.yamareviewer.YamaReviewerPluginTest'

dependencies {
	compileOnly group: 'net.runelite', name:'client', version: runeLiteVersion

	compileOnly 'org.projectlombok:lombok:1.18.30'
	annotationProcessor 'org.projectlombok:lombok:1.18.30'

	testImplementation 'junit:junit:4.12'
	testImplementation 'org.mockito:mockito-core:5.14.2'
	testImplementation 'com.tngtech.archunit:archunit-junit4:1.3.0'
	testImplementation group: 'net.runelite', name:'client', version: runeLiteVersion
	testImplementation group: 'net.runelite', name:'jshell', version: runeLiteVersion
}

group = 'com.yamareviewer'
version = '0.1.0'

tasks.withType(JavaCompile).configureEach {
	options.encoding = 'UTF-8'
	options.release.set(11)
}

tasks.register('run', JavaExec) {
	classpath = sourceSets.test.runtimeClasspath
	mainClass = pluginMainClass

	jvmArgs "-ea"
	args "--developer-mode", "--debug"
}

tasks.register('shadowJar', Jar) {
	dependsOn configurations.testRuntimeClasspath
	manifest {
		attributes('Main-Class': pluginMainClass, 'Multi-Release': true)
	}

	duplicatesStrategy = DuplicatesStrategy.EXCLUDE
	from sourceSets.main.output
	from sourceSets.test.output
	from {
		configurations.testRuntimeClasspath.collect { file ->
			file.isDirectory() ? file : zipTree(file)
		}
	}

	exclude 'META-INF/INDEX.LIST'
	exclude 'META-INF/*.SF'
	exclude 'META-INF/*.DSA'
	exclude 'META-INF/*.RSA'
	exclude '**/module-info.class'

	group = BasePlugin.BUILD_GROUP
	archiveClassifier.set('shadow')
	archiveFileName.set("${rootProject.name}-${project.version}-all.jar")
}
```

- [ ] **Step 3: Write `settings.gradle` and `runelite-plugin.properties`**

`settings.gradle`:

```groovy
rootProject.name = 'yama-reviewer'
```

`runelite-plugin.properties`:

```properties
displayName=Yama Reviewer
author=Stefan Willems
description=Records Yama kills and reviews them after the kill ends. Silent during the fight.
tags=yama,pvm,review,prayer,duo
version=
plugins=com.yamareviewer.YamaReviewerPlugin
build=standard
```

- [ ] **Step 4: Write `LICENSE` (BSD-2-Clause) and a README stub**

`LICENSE`:

```text
BSD 2-Clause License

Copyright (c) 2026, Stefan Willems
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

`README.md`:

```markdown
# Yama Reviewer

A RuneLite plugin that records your Yama kills and reviews them **after** the kill ends.
During the fight it shows, says and changes nothing.

Work in progress. Design: `docs/superpowers/specs/2026-09-24-yama-reviewer-design.md`.

NPC and region IDs are credited to [Yama Utilities](https://github.com/sreilly64/yama-utilities) (BSD-2).
```

- [ ] **Step 5: Write the config, plugin skeleton and dev launcher**

`src/main/java/com/yamareviewer/YamaReviewerConfig.java`:

```java
package com.yamareviewer;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(YamaReviewerConfig.GROUP)
public interface YamaReviewerConfig extends Config
{
	String GROUP = "yamareviewer";

	@ConfigSection(
		name = "Development",
		description = "Settings used while capturing logging kills",
		position = 100,
		closedByDefault = true
	)
	String development = "development";

	@Range(min = 1, max = 200)
	@ConfigItem(
		keyName = "rawLogsKept",
		name = "Raw logs kept",
		description = "How many recent kills keep their raw event log, so their reviews can be recalculated",
		position = 1
	)
	default int rawLogsKept()
	{
		return 20;
	}

	@ConfigItem(
		keyName = "captureMode",
		name = "Capture mode",
		description = "Also record other actors and every animated object, for confirming IDs after a game update",
		position = 101,
		section = development
	)
	default boolean captureMode()
	{
		return false;
	}
}
```

`src/main/java/com/yamareviewer/YamaReviewerPlugin.java`:

```java
package com.yamareviewer;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
	name = "Yama Reviewer",
	description = "Records Yama kills and reviews them after the kill ends. Silent during the fight.",
	tags = {"yama", "pvm", "review", "prayer", "duo"},
	internalName = "yama-reviewer"
)
public class YamaReviewerPlugin extends Plugin
{
	@Provides
	YamaReviewerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(YamaReviewerConfig.class);
	}

	@Override
	protected void startUp()
	{
		log.debug("Yama Reviewer started");
	}

	@Override
	protected void shutDown()
	{
		log.debug("Yama Reviewer stopped");
	}
}
```

`src/test/java/com/yamareviewer/YamaReviewerPluginTest.java`:

```java
package com.yamareviewer;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class YamaReviewerPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(YamaReviewerPlugin.class);
		RuneLite.main(args);
	}
}
```

- [ ] **Step 6: Build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add build.gradle settings.gradle runelite-plugin.properties gradlew gradlew.bat gradle .gitignore LICENSE README.md src
git commit -m "chore: scaffold Yama Reviewer from the RuneLite example plugin"
```

---

### Task 2: Domain events and the kill log

**Files:**
- Create in `src/main/java/com/yamareviewer/domain/event/`: `ActorKind`, `Actor`, `Position`, `EntryChoice`, `EndReason`, `ProtectionPrayer`, `HitsplatKind`, `SnapshotKind`, `GameStateKind`, `SupplyItem`, `DomainEvent`, `EventType`, and the events `EntryChosen`, `FightStarted`, `FightEnded`, `PlayerSeen`, `PlayerLeft`, `GameStateObserved`, `NpcSpawnObserved`, `NpcDespawnObserved`, `NpcChangedObserved`, `ObjectSpawnObserved`, `ObjectDespawnObserved`, `ObjectAnimationObserved`, `OverheadTextObserved`, `GameMessageObserved`, `VarbitObserved`, `WidgetTextObserved`, `AnimationObserved`, `GraphicObserved`, `GroundGraphicObserved`, `ProjectileObserved`, `HitsplatObserved`, `TickState`, `InventoryDelta`, `SuppliesSnapshot`
- Create: `src/main/java/com/yamareviewer/domain/model/KillHeader.java`, `KillLog.java`
- Test: `src/test/java/com/yamareviewer/domain/event/ActorTest.java`, `DomainEventsTest.java`, `src/test/java/com/yamareviewer/domain/model/KillLogTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: the types above with Lombok `@Value` getters; `Actor.SELF/PARTNER/YAMA/JUDGE`, `Actor.flare(int)`, `Actor.npc(int)`, `Actor.other(String)`, `Actor.isPlayer()`; `EventType.of(DomainEvent)`, `EventType.byName(String)`, `typeName()`, `eventClass()`; `KillHeader(String killId, long startEpochMs, long endEpochMs, String pluginVersion, int schemaVersion, String idsFingerprint, boolean capture)`; `KillLog.of(KillHeader, List<? extends DomainEvent>, int skippedEvents)`, `eventsOf(Class<T>)`, `endReason()`, `lastTick()`, `KillLog.SCHEMA_VERSION = 1`.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/yamareviewer/domain/event/ActorTest.java`:

```java
package com.yamareviewer.domain.event;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ActorTest
{
	@Test
	public void playersAreSelfAndPartnerOnly()
	{
		assertTrue(Actor.SELF.isPlayer());
		assertTrue(Actor.PARTNER.isPlayer());
		assertFalse(Actor.YAMA.isPlayer());
		assertFalse(Actor.other("Stranger").isPlayer());
	}

	@Test
	public void flaresAreIdentifiedByNpcIndex()
	{
		assertEquals(Actor.flare(7), Actor.flare(7));
		assertEquals(ActorKind.FLARE, Actor.flare(7).getKind());
		assertEquals(7, Actor.flare(7).getRef());
	}

	@Test
	public void roleNpcsCarryTheirNpcId()
	{
		assertEquals(ActorKind.NPC, Actor.npc(14182).getKind());
		assertEquals(14182, Actor.npc(14182).getRef());
	}
}
```

`src/test/java/com/yamareviewer/domain/event/DomainEventsTest.java`:

```java
package com.yamareviewer.domain.event;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class DomainEventsTest
{
	@Test
	public void typeNamesAreUnique()
	{
		long distinct = Arrays.stream(EventType.values()).map(EventType::typeName).distinct().count();
		assertEquals(EventType.values().length, distinct);
	}

	@Test
	public void everyEventClassHasAType()
	{
		Set<String> eventClasses = new ClassFileImporter().importPackages("com.yamareviewer.domain.event").stream()
			.filter(c -> c.isAssignableTo(DomainEvent.class) && !c.isInterface())
			.map(JavaClass::getName)
			.collect(Collectors.toSet());
		Set<String> typed = Arrays.stream(EventType.values()).map(t -> t.eventClass().getName()).collect(Collectors.toSet());
		assertEquals(eventClasses, typed);
	}

	@Test
	public void typesResolveBothWays()
	{
		assertEquals(EventType.HITSPLAT, EventType.of(new HitsplatObserved(1, Actor.SELF, HitsplatKind.DAMAGE, 5, 1, false)));
		assertEquals(Optional.of(EventType.TICK), EventType.byName("tick"));
		assertEquals(Optional.empty(), EventType.byName("from-the-future"));
	}

	@Test
	public void missingCollectionsReadAsEmpty()
	{
		assertTrue(new TickState(1, null, 99, 99, 100, 100, -1, null, null, null).getPrayers().isEmpty());
		assertTrue(new SuppliesSnapshot(1, SnapshotKind.START, null).getItems().isEmpty());
	}
}
```

`src/test/java/com/yamareviewer/domain/model/KillLogTest.java`:

```java
package com.yamareviewer.domain.model;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.FightEnded;
import com.yamareviewer.domain.event.HitsplatKind;
import com.yamareviewer.domain.event.HitsplatObserved;
import com.yamareviewer.domain.event.VarbitObserved;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class KillLogTest
{
	private static KillHeader header()
	{
		return new KillHeader("kill-1", 1_000L, 2_000L, "0.1.0", KillLog.SCHEMA_VERSION, "f00d", false);
	}

	@Test
	public void eventsOfFiltersByTypeKeepingOrder()
	{
		KillLog log = KillLog.of(header(), List.of(
			new AnimationObserved(1, Actor.YAMA, 100),
			new HitsplatObserved(2, Actor.SELF, HitsplatKind.DAMAGE, 16, 3, false),
			new AnimationObserved(3, Actor.SELF, 200)), 0);

		List<AnimationObserved> animations = log.eventsOf(AnimationObserved.class);

		assertEquals(2, animations.size());
		assertEquals(100, animations.get(0).getAnimationId());
		assertEquals(200, animations.get(1).getAnimationId());
	}

	@Test
	public void endReasonAndLastTickComeFromFightEnded()
	{
		KillLog log = KillLog.of(header(), List.of(new FightEnded(40, EndReason.YAMA_DIED)), 0);

		assertEquals(EndReason.YAMA_DIED, log.endReason());
		assertEquals(40, log.lastTick());
	}

	@Test
	public void missingFightEndedCountsAsLeft()
	{
		KillLog log = KillLog.of(header(), List.of(new AnimationObserved(5, Actor.YAMA, 1)), 0);

		assertEquals(EndReason.LEFT, log.endReason());
	}

	@Test(expected = UnsupportedOperationException.class)
	public void eventsCannotBeModified()
	{
		KillLog log = KillLog.of(header(), List.of(new VarbitObserved(0, 1, 1)), 0);

		log.getEvents().add(new VarbitObserved(1, 1, 2));
	}

	@Test
	public void laterChangesToTheSourceListDoNotLeakIn()
	{
		List<DomainEvent> source = new ArrayList<>();
		KillLog log = KillLog.of(header(), source, 0);

		source.add(new VarbitObserved(1, 1, 1));

		assertTrue(log.getEvents().isEmpty());
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'com.yamareviewer.domain.*'`
Expected: FAIL — compilation errors, `cannot find symbol` for `Actor`, `EventType`, `KillLog`.

- [ ] **Step 3: Write the value types**

`ActorKind.java`:

```java
package com.yamareviewer.domain.event;

public enum ActorKind
{
	SELF,
	PARTNER,
	YAMA,
	JUDGE,
	FLARE,
	NPC,
	OTHER
}
```

`Actor.java`:

```java
package com.yamareviewer.domain.event;

import lombok.Value;

/**
 * Who an event is about, resolved when it is recorded.
 * {@code ref} is the NPC index for {@link ActorKind#FLARE}, the NPC id for {@link ActorKind#NPC}, otherwise -1.
 * {@code name} is only set for {@link ActorKind#OTHER}.
 */
@Value
public class Actor
{
	public static final Actor SELF = new Actor(ActorKind.SELF, -1, null);
	public static final Actor PARTNER = new Actor(ActorKind.PARTNER, -1, null);
	public static final Actor YAMA = new Actor(ActorKind.YAMA, -1, null);
	public static final Actor JUDGE = new Actor(ActorKind.JUDGE, -1, null);

	ActorKind kind;
	int ref;
	String name;

	public static Actor flare(int npcIndex)
	{
		return new Actor(ActorKind.FLARE, npcIndex, null);
	}

	public static Actor npc(int npcId)
	{
		return new Actor(ActorKind.NPC, npcId, null);
	}

	public static Actor other(String name)
	{
		return new Actor(ActorKind.OTHER, -1, name);
	}

	public boolean isPlayer()
	{
		return kind == ActorKind.SELF || kind == ActorKind.PARTNER;
	}
}
```

`Position.java`:

```java
package com.yamareviewer.domain.event;

import lombok.Value;

/** A template (real-world) coordinate, also inside the instance. */
@Value
public class Position
{
	int x;
	int y;
	int plane;
}
```

The enums, one file each:

```java
package com.yamareviewer.domain.event;

public enum EntryChoice
{
	TRAVEL,
	JOIN
}
```

```java
package com.yamareviewer.domain.event;

public enum EndReason
{
	YAMA_DIED,
	PLAYER_DIED,
	LEFT
}
```

```java
package com.yamareviewer.domain.event;

public enum ProtectionPrayer
{
	MAGIC,
	MISSILES,
	MELEE
}
```

```java
package com.yamareviewer.domain.event;

public enum HitsplatKind
{
	DAMAGE,
	BLOCK,
	HEAL,
	OTHER
}
```

```java
package com.yamareviewer.domain.event;

public enum SnapshotKind
{
	START,
	END
}
```

```java
package com.yamareviewer.domain.event;

public enum GameStateKind
{
	LOADING,
	LOGGED_IN,
	HOPPING,
	LOGIN_SCREEN,
	CONNECTION_LOST,
	OTHER
}
```

`SupplyItem.java`:

```java
package com.yamareviewer.domain.event;

import lombok.Value;

/** One item line of a supplies snapshot, priced when the snapshot was taken. */
@Value
public class SupplyItem
{
	int itemId;
	String name;
	int quantity;
	int gePrice;
	int haPrice;
}
```

- [ ] **Step 4: Write `DomainEvent`, the events and `EventType`**

`DomainEvent.java`:

```java
package com.yamareviewer.domain.event;

/**
 * A raw observation made during a fight. Events never contain a classification
 * such as "magic attack" or "wrong prayer"; that happens after the kill.
 * Collections passed into events must be unmodifiable copies.
 */
public interface DomainEvent
{
	/** Game ticks since the fight started. */
	int getTick();
}
```

Each event is a Lombok `@Value` class implementing `DomainEvent`, one file each:

```java
package com.yamareviewer.domain.event;

import lombok.Value;

@Value
public class EntryChosen implements DomainEvent
{
	int tick;
	EntryChoice choice;
}
```

```java
package com.yamareviewer.domain.event;

import lombok.Value;

@Value
public class FightStarted implements DomainEvent
{
	int tick;
	String selfName;
	Position selfPosition;
}
```

```java
package com.yamareviewer.domain.event;

import lombok.Value;

@Value
public class FightEnded implements DomainEvent
{
	int tick;
	EndReason reason;
}
```

```java
package com.yamareviewer.domain.event;

import lombok.Value;

/** Another player is in the arena (present at fight start, or loaded in later). */
@Value
public class PlayerSeen implements DomainEvent
{
	int tick;
	String name;
}
```

```java
package com.yamareviewer.domain.event;

import lombok.Value;

@Value
public class PlayerLeft implements DomainEvent
{
	int tick;
	String name;
}
```

```java
package com.yamareviewer.domain.event;

import lombok.Value;

@Value
public class GameStateObserved implements DomainEvent
{
	int tick;
	GameStateKind state;
}
```

```java
package com.yamareviewer.domain.event;

import lombok.Value;

@Value
public class NpcSpawnObserved implements DomainEvent
{
	int tick;
	Actor actor;
	int npcId;
	int npcIndex;
}
```

```java
package com.yamareviewer.domain.event;

import lombok.Value;

@Value
public class NpcDespawnObserved implements DomainEvent
{
	int tick;
	Actor actor;
	int npcId;
	int npcIndex;
	/** RuneLite's NpcUtil.isDying at despawn; false for a scene reload. */
	boolean dying;
}
```

```java
package com.yamareviewer.domain.event;

import lombok.Value;

@Value
public class NpcChangedObserved implements DomainEvent
{
	int tick;
	Actor actor;
	int oldId;
	int newId;
}
```

```java
package com.yamareviewer.domain.event;

import lombok.Value;

@Value
public class ObjectSpawnObserved implements DomainEvent
{
	int tick;
	int objectId;
	Position position;
}
```

```java
package com.yamareviewer.domain.event;

import lombok.Value;

@Value
public class ObjectDespawnObserved implements DomainEvent
{
	int tick;
	int objectId;
	Position position;
}
```

```java
package com.yamareviewer.domain.event;

import lombok.Value;

@Value
public class ObjectAnimationObserved implements DomainEvent
{
	int tick;
	int objectId;
	Position position;
	int animationId;
}
```

```java
package com.yamareviewer.domain.event;

import lombok.Value;

@Value
public class OverheadTextObserved implements DomainEvent
{
	int tick;
	Actor actor;
	String text;
}
```

```java
package com.yamareviewer.domain.event;

import lombok.Value;

/** A system message (ChatMessageType.GAMEMESSAGE), colour tags kept: they carry meaning (shadow or fire). */
@Value
public class GameMessageObserved implements DomainEvent
{
	int tick;
	String text;
}
```

```java
package com.yamareviewer.domain.event;

import lombok.Value;

@Value
public class VarbitObserved implements DomainEvent
{
	int tick;
	int varbitId;
	int value;
}
```

```java
package com.yamareviewer.domain.event;

import lombok.Value;

@Value
public class WidgetTextObserved implements DomainEvent
{
	int tick;
	int componentId;
	String text;
}
```

```java
package com.yamareviewer.domain.event;

import lombok.Value;

@Value
public class AnimationObserved implements DomainEvent
{
	int tick;
	Actor actor;
	int animationId;
}
```

```java
package com.yamareviewer.domain.event;

import lombok.Value;

@Value
public class GraphicObserved implements DomainEvent
{
	int tick;
	Actor actor;
	int graphicId;
}
```

```java
package com.yamareviewer.domain.event;

import lombok.Value;

@Value
public class GroundGraphicObserved implements DomainEvent
{
	int tick;
	int graphicId;
	Position position;
}
```

```java
package com.yamareviewer.domain.event;

import lombok.Value;

@Value
public class ProjectileObserved implements DomainEvent
{
	int tick;
	int projectileId;
	/** Null when the projectile targets a tile. */
	Actor target;
	int endTick;
}
```

```java
package com.yamareviewer.domain.event;

import lombok.Value;

@Value
public class HitsplatObserved implements DomainEvent
{
	int tick;
	Actor target;
	HitsplatKind kind;
	int amount;
	int rawType;
	/** True when the local player dealt this hitsplat (RuneLite's Hitsplat.isMine()). */
	boolean mine;
}
```

```java
package com.yamareviewer.domain.event;

import java.util.Set;
import lombok.Value;

/** State at the end of a tick; recorded on GameTick, after every other event of that tick. */
@Value
public class TickState implements DomainEvent
{
	int tick;
	Set<ProtectionPrayer> prayers;
	int hitpoints;
	int prayerPoints;
	/** Percent, 0-100. */
	int specEnergy;
	/** Percent, 0-100. */
	int runEnergy;
	/** -1 when nothing is wielded. */
	int weaponId;
	/** Null when Yama targets nobody. */
	Actor yamaTarget;
	Position selfPosition;
	/** Null in solo or when the partner is not visible. */
	Position partnerPosition;

	public Set<ProtectionPrayer> getPrayers()
	{
		return prayers == null ? Set.of() : prayers;
	}
}
```

```java
package com.yamareviewer.domain.event;

import lombok.Value;

@Value
public class InventoryDelta implements DomainEvent
{
	int tick;
	int itemId;
	String name;
	int change;
}
```

```java
package com.yamareviewer.domain.event;

import java.util.List;
import lombok.Value;

@Value
public class SuppliesSnapshot implements DomainEvent
{
	int tick;
	SnapshotKind kind;
	/** Inventory plus rune pouch contents, aggregated per item id. */
	List<SupplyItem> items;

	public List<SupplyItem> getItems()
	{
		return items == null ? List.of() : items;
	}
}
```

`EventType.java`:

```java
package com.yamareviewer.domain.event;

import java.util.Optional;

/** The stable name written into logs for each event class. Never rename a released type name. */
public enum EventType
{
	ENTRY("entry", EntryChosen.class),
	FIGHT_START("fight-start", FightStarted.class),
	FIGHT_END("fight-end", FightEnded.class),
	PLAYER_SEEN("player-seen", PlayerSeen.class),
	PLAYER_LEFT("player-left", PlayerLeft.class),
	GAME_STATE("game-state", GameStateObserved.class),
	NPC_SPAWN("npc-spawn", NpcSpawnObserved.class),
	NPC_DESPAWN("npc-despawn", NpcDespawnObserved.class),
	NPC_CHANGED("npc-changed", NpcChangedObserved.class),
	OBJECT_SPAWN("object-spawn", ObjectSpawnObserved.class),
	OBJECT_DESPAWN("object-despawn", ObjectDespawnObserved.class),
	OBJECT_ANIMATION("object-animation", ObjectAnimationObserved.class),
	OVERHEAD("overhead", OverheadTextObserved.class),
	GAME_MESSAGE("game-message", GameMessageObserved.class),
	VARBIT("varbit", VarbitObserved.class),
	WIDGET_TEXT("widget-text", WidgetTextObserved.class),
	ANIMATION("animation", AnimationObserved.class),
	GRAPHIC("graphic", GraphicObserved.class),
	GROUND_GRAPHIC("ground-graphic", GroundGraphicObserved.class),
	PROJECTILE("projectile", ProjectileObserved.class),
	HITSPLAT("hitsplat", HitsplatObserved.class),
	TICK("tick", TickState.class),
	INVENTORY("inventory", InventoryDelta.class),
	SUPPLIES("supplies", SuppliesSnapshot.class);

	private final String typeName;
	private final Class<? extends DomainEvent> eventClass;

	EventType(String typeName, Class<? extends DomainEvent> eventClass)
	{
		this.typeName = typeName;
		this.eventClass = eventClass;
	}

	public String typeName()
	{
		return typeName;
	}

	public Class<? extends DomainEvent> eventClass()
	{
		return eventClass;
	}

	public static EventType of(DomainEvent event)
	{
		for (EventType type : values())
		{
			if (type.eventClass == event.getClass())
			{
				return type;
			}
		}
		throw new IllegalArgumentException("No event type for " + event.getClass().getName());
	}

	public static Optional<EventType> byName(String typeName)
	{
		for (EventType type : values())
		{
			if (type.typeName.equals(typeName))
			{
				return Optional.of(type);
			}
		}
		return Optional.empty();
	}
}
```

- [ ] **Step 5: Write `KillHeader` and `KillLog`**

`src/main/java/com/yamareviewer/domain/model/KillHeader.java`:

```java
package com.yamareviewer.domain.model;

import lombok.Value;

/** Only facts about the recording itself; every game observation is an event. */
@Value
public class KillHeader
{
	String killId;
	long startEpochMs;
	long endEpochMs;
	String pluginVersion;
	int schemaVersion;
	/** IdRegistry.fingerprint() of the IDs used while recording. */
	String idsFingerprint;
	boolean capture;
}
```

`src/main/java/com/yamareviewer/domain/model/KillLog.java`:

```java
package com.yamareviewer.domain.model;

import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.FightEnded;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Value;

/** The frozen, append-only event stream of one kill: the write model. */
@Value
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class KillLog
{
	public static final int SCHEMA_VERSION = 1;

	KillHeader header;
	List<DomainEvent> events;
	/** Events of an unknown type that could not be read back. */
	int skippedEvents;

	public static KillLog of(KillHeader header, List<? extends DomainEvent> events, int skippedEvents)
	{
		return new KillLog(header, List.copyOf(events), skippedEvents);
	}

	public <T extends DomainEvent> List<T> eventsOf(Class<T> type)
	{
		List<T> result = new ArrayList<>();
		for (DomainEvent event : events)
		{
			if (type.isInstance(event))
			{
				result.add(type.cast(event));
			}
		}
		return result;
	}

	public EndReason endReason()
	{
		List<FightEnded> ended = eventsOf(FightEnded.class);
		return ended.isEmpty() ? EndReason.LEFT : ended.get(ended.size() - 1).getReason();
	}

	public int lastTick()
	{
		return events.isEmpty() ? 0 : events.get(events.size() - 1).getTick();
	}
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.yamareviewer.domain.*'`
Expected: PASS (12 tests).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/yamareviewer/domain src/test/java/com/yamareviewer/domain
git commit -m "feat: add domain events with stable type names and the kill log"
```

---

### Task 3: Roles, tunables and built-in IDs

**Files:**
- Create: `src/main/java/com/yamareviewer/domain/ids/RoleKind.java`, `Role.java`, `PrayerCheck.java`, `Rules.java`, `IdRegistry.java`
- Create: `src/main/java/com/yamareviewer/adapter/ids/BuiltInIds.java`
- Test: `src/test/java/com/yamareviewer/domain/ids/IdRegistryTest.java`, `src/test/java/com/yamareviewer/adapter/ids/BuiltInIdsTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `Role` (`kind()`), `RoleKind`, `PrayerCheck {CAST, HITSPLAT}`, `Rules` (Lombok `@Value @Builder(toBuilder = true)`, `Rules.DEFAULT`), `IdRegistry(Map<Role, Set<Integer>>, Map<Role, Set<String>>)` with `ids`, `texts`, `is`, `matchesText`, `isCaptured`, `roleOf(RoleKind, int)`, `fingerprint()`; `BuiltInIds.registry()`.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/yamareviewer/domain/ids/IdRegistryTest.java`:

```java
package com.yamareviewer.domain.ids;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class IdRegistryTest
{
	private final IdRegistry registry = new IdRegistry(
		Map.of(
			Role.YAMA, Set.of(100),
			Role.YAMA_STANDARD_ATTACK, Set.of(200, 201),
			Role.IMPACT_MAGIC, Set.of()),
		Map.of(Role.PHASE_TRANSITION_TEXT, Set.of("Enough.")));

	@Test
	public void matchesIdsByRole()
	{
		assertTrue(registry.is(Role.YAMA, 100));
		assertFalse(registry.is(Role.YAMA, 200));
		assertEquals(Set.of(200, 201), registry.ids(Role.YAMA_STANDARD_ATTACK));
	}

	@Test
	public void emptyOrMissingRolesAreUncaptured()
	{
		assertTrue(registry.isCaptured(Role.YAMA));
		assertFalse(registry.isCaptured(Role.IMPACT_MAGIC));
		assertFalse(registry.isCaptured(Role.CRASH_FIREBALL));
		assertTrue(registry.isCaptured(Role.PHASE_TRANSITION_TEXT));
	}

	@Test
	public void findsTheRoleOfAnIdWithinAKind()
	{
		assertEquals(Optional.of(Role.YAMA_STANDARD_ATTACK), registry.roleOf(RoleKind.ANIMATION, 201));
		assertEquals(Optional.empty(), registry.roleOf(RoleKind.GRAPHIC, 201));
	}

	@Test
	public void matchesOverheadTextExactly()
	{
		assertTrue(registry.matchesText(Role.PHASE_TRANSITION_TEXT, "Enough."));
		assertFalse(registry.matchesText(Role.PHASE_TRANSITION_TEXT, "enough"));
		assertFalse(registry.matchesText(Role.PHASE_TRANSITION_TEXT, null));
	}

	@Test
	public void messagesMatchByPrefixWithoutColourTags()
	{
		IdRegistry messages = new IdRegistry(Map.of(), Map.of(Role.PRAYER_DISABLED_MESSAGE, Set.of("You've been injured")));

		assertTrue(messages.matchesText(Role.PRAYER_DISABLED_MESSAGE,
			"<col=ef1020>You've been injured and can't use protection prayers!</col>"));
		assertFalse(messages.matchesText(Role.PRAYER_DISABLED_MESSAGE, "Something else"));
		assertTrue(messages.isCaptured(Role.PRAYER_DISABLED_MESSAGE));
	}

	@Test
	public void fingerprintFollowsTheContent()
	{
		IdRegistry same = new IdRegistry(
			Map.of(Role.YAMA, Set.of(100), Role.YAMA_STANDARD_ATTACK, Set.of(201, 200)),
			Map.of(Role.PHASE_TRANSITION_TEXT, Set.of("Enough.")));
		IdRegistry changed = new IdRegistry(
			Map.of(Role.YAMA, Set.of(101), Role.YAMA_STANDARD_ATTACK, Set.of(200, 201)),
			Map.of(Role.PHASE_TRANSITION_TEXT, Set.of("Enough.")));

		assertEquals(registry.fingerprint(), same.fingerprint());
		assertNotEquals(registry.fingerprint(), changed.fingerprint());
	}

	@Test(expected = UnsupportedOperationException.class)
	public void idSetsCannotBeModified()
	{
		registry.ids(Role.YAMA).add(5);
	}
}
```

`src/test/java/com/yamareviewer/adapter/ids/BuiltInIdsTest.java`:

```java
package com.yamareviewer.adapter.ids;

import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.RoleKind;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class BuiltInIdsTest
{
	/** Roles with no known constant yet; the logging kills fill them. */
	private static final Set<Role> TO_CAPTURE = EnumSet.of(Role.JUDGE_FIRE_SURGE, Role.CRASH_FIREBALL, Role.SPEC_PURGING_STAFF, Role.SHADOW_POOL);

	private final IdRegistry ids = BuiltInIds.registry();

	@Test
	public void everyRoleHasAValueExceptTheOnesToCapture()
	{
		for (Role role : Role.values())
		{
			assertEquals(role.name(), !TO_CAPTURE.contains(role), ids.isCaptured(role));
		}
	}

	@Test
	public void recordingRolesMatchYamaUtilities()
	{
		assertEquals(Set.of(14176), ids.ids(Role.YAMA));
		assertEquals(Set.of(14180), ids.ids(Role.JUDGE));
		assertEquals(Set.of(14179), ids.ids(Role.VOID_FLARE));
		assertEquals(Set.of(6045), ids.ids(Role.YAMAS_DOMAIN));
		assertTrue(ids.ids(Role.VOICE_OF_YAMA).contains(14185));
	}

	@Test
	public void noIdBelongsToTwoRolesOfTheSameKind()
	{
		for (RoleKind kind : RoleKind.values())
		{
			Set<Integer> seen = new HashSet<>();
			for (Role role : Role.values())
			{
				if (role.kind() != kind)
				{
					continue;
				}
				for (int id : ids.ids(role))
				{
					assertTrue(role + " reuses " + id, seen.add(id));
				}
			}
		}
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'com.yamareviewer.domain.ids.*' --tests 'com.yamareviewer.adapter.ids.*'`
Expected: FAIL — `cannot find symbol` for `IdRegistry`, `Role`, `BuiltInIds`.

- [ ] **Step 3: Write the ID types**

`RoleKind.java`:

```java
package com.yamareviewer.domain.ids;

public enum RoleKind
{
	NPC,
	REGION,
	OVERHEAD,
	MESSAGE,
	VARBIT,
	WIDGET,
	OBJECT,
	ANIMATION,
	GRAPHIC,
	ITEM
}
```

`Role.java`:

```java
package com.yamareviewer.domain.ids;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** Everything the plugin needs to recognise, by what it means rather than by number (spec 5.5). */
public enum Role
{
	YAMA(RoleKind.NPC),
	JUDGE(RoleKind.NPC),
	VOID_FLARE(RoleKind.NPC),
	VOICE_OF_YAMA(RoleKind.NPC),
	METEOR_NPC(RoleKind.NPC),
	JUDGE_FIRE_SURGE(RoleKind.NPC),

	YAMAS_DOMAIN(RoleKind.REGION),

	PHASE_TRANSITION_TEXT(RoleKind.OVERHEAD),

	PRAYER_DISABLED_MESSAGE(RoleKind.MESSAGE),
	GLYPH_CONJURE_MESSAGE(RoleKind.MESSAGE),

	PHASE_VARBIT(RoleKind.VARBIT),

	CONTRACT_NAME_WIDGET(RoleKind.WIDGET),

	CONTRACT_ITEM_FORFEIT_BREATH(RoleKind.ITEM),
	CONTRACT_ITEM_GLYPHIC_ATTENUATION(RoleKind.ITEM),
	CONTRACT_ITEM_SENSORY_CLOUDING(RoleKind.ITEM),
	CONTRACT_ITEM_DIVINE_SEVERANCE(RoleKind.ITEM),
	CONTRACT_ITEM_BLOODIED_BLOWS(RoleKind.ITEM),
	CONTRACT_ITEM_FAMILIAR(RoleKind.ITEM),
	CONTRACT_ITEM_CATALYST(RoleKind.ITEM),
	CONTRACT_ITEM_WORM(RoleKind.ITEM),
	CONTRACT_ITEM_SHARD(RoleKind.ITEM),
	CONTRACT_ITEM_OATHPLATE(RoleKind.ITEM),
	CONTRACT_ITEM_HARMONY(RoleKind.ITEM),
	WEAPON_EMBERLIGHT(RoleKind.ITEM),
	WEAPON_ELDER_MAUL(RoleKind.ITEM),
	WEAPON_DRAGON_WARHAMMER(RoleKind.ITEM),
	WEAPON_BANDOS_GODSWORD(RoleKind.ITEM),
	WEAPON_ACCURSED_SCEPTRE(RoleKind.ITEM),
	WEAPON_EYE_OF_AYAK(RoleKind.ITEM),
	WEAPON_SOULFLAME_HORN(RoleKind.ITEM),
	WEAPON_PURGING_STAFF(RoleKind.ITEM),
	WEAPON_SARADOMIN_GODSWORD(RoleKind.ITEM),

	GLYPH_FIRE(RoleKind.OBJECT),
	GLYPH_SHADOW(RoleKind.OBJECT),

	YAMA_STANDARD_ATTACK(RoleKind.ANIMATION),
	YAMA_MELEE(RoleKind.ANIMATION),
	YAMA_FLARE_SUMMON(RoleKind.ANIMATION),
	SHADOW_STOMP(RoleKind.ANIMATION),
	FLARE_EXPLODE(RoleKind.ANIMATION),
	FLARE_DEATH(RoleKind.ANIMATION),
	SPEC_EMBERLIGHT(RoleKind.ANIMATION),
	SPEC_ELDER_MAUL(RoleKind.ANIMATION),
	SPEC_DRAGON_WARHAMMER(RoleKind.ANIMATION),
	SPEC_BANDOS_GODSWORD(RoleKind.ANIMATION),
	SPEC_ACCURSED_SCEPTRE(RoleKind.ANIMATION),
	SPEC_EYE_OF_AYAK(RoleKind.ANIMATION),
	SPEC_SOULFLAME_HORN(RoleKind.ANIMATION),
	SPEC_PURGING_STAFF(RoleKind.ANIMATION),
	SPEC_SARADOMIN_GODSWORD(RoleKind.ANIMATION),

	YAMA_CAST_MAGIC(RoleKind.GRAPHIC),
	YAMA_CAST_RANGED(RoleKind.GRAPHIC),
	IMPACT_MAGIC(RoleKind.GRAPHIC),
	IMPACT_RANGED(RoleKind.GRAPHIC),
	CRASH_IMPACT(RoleKind.GRAPHIC),
	CRASH_FIREBALL(RoleKind.GRAPHIC),
	SHADOW_WAVE(RoleKind.GRAPHIC),
	FIRE_STREAK(RoleKind.GRAPHIC),
	FIRE_ATTACK(RoleKind.GRAPHIC),
	METEOR_STRIKE(RoleKind.GRAPHIC),
	GLYPH_PROTECTION(RoleKind.GRAPHIC),
	PHASE_TRANSITION_GRAPHIC(RoleKind.GRAPHIC),
	FLARE_HEAL(RoleKind.GRAPHIC),
	FLARE_HIT(RoleKind.GRAPHIC),
	SHADOW_POOL(RoleKind.GRAPHIC);

	/** The eleven contract items, one role per contract (spec 6.3.1). */
	public static final Set<Role> CONTRACT_ITEMS = Collections.unmodifiableSet(EnumSet.of(
		CONTRACT_ITEM_FORFEIT_BREATH, CONTRACT_ITEM_GLYPHIC_ATTENUATION, CONTRACT_ITEM_SENSORY_CLOUDING,
		CONTRACT_ITEM_DIVINE_SEVERANCE, CONTRACT_ITEM_BLOODIED_BLOWS, CONTRACT_ITEM_FAMILIAR,
		CONTRACT_ITEM_CATALYST, CONTRACT_ITEM_WORM, CONTRACT_ITEM_SHARD, CONTRACT_ITEM_OATHPLATE,
		CONTRACT_ITEM_HARMONY));

	private final RoleKind kind;

	Role(RoleKind kind)
	{
		this.kind = kind;
	}

	public RoleKind kind()
	{
		return kind;
	}
}
```

`PrayerCheck.java`:

```java
package com.yamareviewer.domain.ids;

/** Which tick decides a prayer outcome (spec 6.5). */
public enum PrayerCheck
{
	CAST,
	HITSPLAT
}
```

`Rules.java`:

```java
package com.yamareviewer.domain.ids;

import lombok.Builder;
import lombok.Value;

/** Tunables of the classification (spec 5.5). Not game IDs. */
@Value
@Builder(toBuilder = true)
public class Rules
{
	public static final Rules DEFAULT = Rules.builder()
		.prayerCheck(PrayerCheck.CAST)
		.prayerCheckOffset(0)
		.blockedMaxHit(3)
		.p1p2AttackCycle(8)
		.p3AttackCycle(7)
		.crashImpactWindow(1)
		.crashSetGap(6)
		.waveDisableWindow(5)
		.specResultWindow(6)
		.statRestoreTicks(100)
		.minAttackCountRatio(0.6)
		.minCycleGapRatio(0.8)
		.minAlternationRatio(0.9)
		.build();

	PrayerCheck prayerCheck;
	int prayerCheckOffset;
	int blockedMaxHit;
	int p1p2AttackCycle;
	int p3AttackCycle;
	int crashImpactWindow;
	int crashSetGap;
	int waveDisableWindow;
	int specResultWindow;
	int statRestoreTicks;
	double minAttackCountRatio;
	double minCycleGapRatio;
	double minAlternationRatio;
}
```

`IdRegistry.java`:

```java
package com.yamareviewer.domain.ids;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * All game IDs by role. A role with no IDs is "uncaptured" and every section that needs it is hidden.
 */
public final class IdRegistry
{
	private final Map<Role, Set<Integer>> ids;
	private final Map<Role, Set<String>> texts;

	public IdRegistry(Map<Role, Set<Integer>> ids, Map<Role, Set<String>> texts)
	{
		this.ids = copy(ids);
		this.texts = copy(texts);
	}

	public Set<Integer> ids(Role role)
	{
		return ids.getOrDefault(role, Set.of());
	}

	public Set<String> texts(Role role)
	{
		return texts.getOrDefault(role, Set.of());
	}

	public boolean is(Role role, int id)
	{
		return ids(role).contains(id);
	}

	/** Overhead roles match exactly; message roles match by prefix after colour tags are removed. */
	public boolean matchesText(Role role, String text)
	{
		if (text == null)
		{
			return false;
		}
		if (role.kind() == RoleKind.MESSAGE)
		{
			String plain = text.replaceAll("<[^>]*>", "");
			return texts(role).stream().anyMatch(plain::startsWith);
		}
		return texts(role).contains(text);
	}

	public boolean isCaptured(Role role)
	{
		boolean textRole = role.kind() == RoleKind.OVERHEAD || role.kind() == RoleKind.MESSAGE;
		return textRole ? !texts(role).isEmpty() : !ids(role).isEmpty();
	}

	/** The first role (in declaration order) of the given kind that contains the id. */
	public Optional<Role> roleOf(RoleKind kind, int id)
	{
		for (Map.Entry<Role, Set<Integer>> entry : ids.entrySet())
		{
			if (entry.getKey().kind() == kind && entry.getValue().contains(id))
			{
				return Optional.of(entry.getKey());
			}
		}
		return Optional.empty();
	}

	/** Changes whenever any mapping changes; stored in every raw log header. */
	public String fingerprint()
	{
		StringBuilder canonical = new StringBuilder();
		for (Role role : Role.values())
		{
			canonical.append(role.name()).append('=');
			ids(role).stream().sorted().forEach(id -> canonical.append(id).append(','));
			texts(role).stream().sorted().forEach(text -> canonical.append(text).append(','));
			canonical.append(';');
		}
		return Integer.toHexString(canonical.toString().hashCode());
	}

	private static <T> Map<Role, Set<T>> copy(Map<Role, Set<T>> source)
	{
		Map<Role, Set<T>> copy = new EnumMap<>(Role.class);
		source.forEach((role, values) -> copy.put(role, Set.copyOf(values)));
		return Collections.unmodifiableMap(copy);
	}
}
```

- [ ] **Step 4: Write `BuiltInIds`**

`src/main/java/com/yamareviewer/adapter/ids/BuiltInIds.java`:

```java
package com.yamareviewer.adapter.ids;

import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.ObjectID1;
import net.runelite.api.gameval.SpotanimID;
import net.runelite.api.gameval.VarbitID;

/**
 * The only place with Yama-specific game IDs. Uses RuneLite's gameval constants, so a RuneLite update
 * that regenerates them after a game update often fixes a renumbering without a plugin update.
 * Mappings marked unconfirmed in spec 5.5 are confirmed by the golden tests of the logging kills.
 * Still to capture: JUDGE_FIRE_SURGE, CRASH_FIREBALL, SPEC_PURGING_STAFF, SHADOW_POOL.
 */
public final class BuiltInIds
{
	/** Yama's Domain, from Yama Utilities; there is no gameval constant for regions. */
	private static final int YAMAS_DOMAIN_REGION = 6045;

	private BuiltInIds()
	{
	}

	public static IdRegistry registry()
	{
		Map<Role, Set<Integer>> ids = new EnumMap<>(Role.class);

		ids.put(Role.YAMA, Set.of(NpcID.YAMA));
		ids.put(Role.JUDGE, Set.of(NpcID.YAMA_JUDGE_OF_YAMA));
		ids.put(Role.VOID_FLARE, Set.of(NpcID.YAMA_VOIDFLARE));
		ids.put(Role.VOICE_OF_YAMA, Set.of(NpcID.VOICE_OF_YAMA_1OP, NpcID.VOICE_OF_YAMA_2OP, NpcID.VOICE_OF_YAMA_3OP));
		ids.put(Role.METEOR_NPC, Set.of(NpcID.YAMA_METEOR_NPC));

		ids.put(Role.YAMAS_DOMAIN, Set.of(YAMAS_DOMAIN_REGION));

		ids.put(Role.PHASE_VARBIT, Set.of(VarbitID.YAMA_TRANSITION_PHASE));

		ids.put(Role.CONTRACT_NAME_WIDGET, Set.of(InterfaceID.YamaContractFight.CONTRACT_NAME));

		ids.put(Role.CONTRACT_ITEM_FORFEIT_BREATH, Set.of(ItemID.YAMA_BINDING_CONTRACT));
		ids.put(Role.CONTRACT_ITEM_GLYPHIC_ATTENUATION, Set.of(ItemID.YAMA_SPECIAL_CONTRACT));
		ids.put(Role.CONTRACT_ITEM_SENSORY_CLOUDING, Set.of(ItemID.YAMA_SPELL_CONTRACT));
		ids.put(Role.CONTRACT_ITEM_DIVINE_SEVERANCE, Set.of(ItemID.YAMA_HEAVYRANGED_CONTRACT));
		ids.put(Role.CONTRACT_ITEM_BLOODIED_BLOWS, Set.of(ItemID.YAMA_2H_CONTRACT));
		ids.put(Role.CONTRACT_ITEM_FAMILIAR, Set.of(ItemID.YAMA_PET_CONTRACT));
		ids.put(Role.CONTRACT_ITEM_CATALYST, Set.of(ItemID.YAMA_CATALYST_CONTRACT));
		ids.put(Role.CONTRACT_ITEM_WORM, Set.of(ItemID.YAMA_WORM_CONTRACT));
		ids.put(Role.CONTRACT_ITEM_SHARD, Set.of(ItemID.YAMA_SHARD_CONTRACT));
		ids.put(Role.CONTRACT_ITEM_OATHPLATE, Set.of(ItemID.YAMA_ARMOUR_CONTRACT));
		ids.put(Role.CONTRACT_ITEM_HARMONY, Set.of(ItemID.YAMA_HORN_CONTRACT));
		ids.put(Role.WEAPON_EMBERLIGHT, Set.of(ItemID.EMBERLIGHT));
		ids.put(Role.WEAPON_ELDER_MAUL, Set.of(ItemID.ELDER_MAUL, ItemID.ELDER_MAUL_ORNAMENT));
		ids.put(Role.WEAPON_DRAGON_WARHAMMER, Set.of(ItemID.DRAGON_WARHAMMER, ItemID.DRAGON_WARHAMMER_ORNAMENT));
		ids.put(Role.WEAPON_BANDOS_GODSWORD, Set.of(ItemID.BGS, ItemID.BGSG));
		ids.put(Role.WEAPON_ACCURSED_SCEPTRE, Set.of(ItemID.WILD_CAVE_ACCURSED_CHARGED, ItemID.WILD_CAVE_ACCURSED_CHARGED_RECOL));
		ids.put(Role.WEAPON_EYE_OF_AYAK, Set.of(ItemID.EYE_OF_AYAK));
		ids.put(Role.WEAPON_SOULFLAME_HORN, Set.of(ItemID.SOULFLAME_HORN));
		ids.put(Role.WEAPON_PURGING_STAFF, Set.of(ItemID.PURGING_STAFF));
		ids.put(Role.WEAPON_SARADOMIN_GODSWORD, Set.of(ItemID.SGS, ItemID.SGSG));

		ids.put(Role.GLYPH_FIRE, Set.of(ObjectID1.FLOORKIT_SUMMONING03_FULL02));
		ids.put(Role.GLYPH_SHADOW, Set.of(ObjectID1.FLOORKIT_SUMMONING03_FULL01));

		ids.put(Role.YAMA_STANDARD_ATTACK, Set.of(AnimationID.NPC_YAMA01_MAGIC01));
		ids.put(Role.YAMA_MELEE, Set.of(AnimationID.NPC_YAMA01_MELEE01));
		ids.put(Role.YAMA_FLARE_SUMMON, Set.of(AnimationID.NPC_YAMA_SUMMON01));
		ids.put(Role.SHADOW_STOMP, Set.of(AnimationID.NPC_YAMA01_STOMP01));
		ids.put(Role.FLARE_EXPLODE, Set.of(AnimationID.NPC_VOIDFLARE_EXPLODE));
		ids.put(Role.FLARE_DEATH, Set.of(AnimationID.NPC_VOIDFLARE_DEATH));
		ids.put(Role.SPEC_EMBERLIGHT, Set.of(AnimationID.HUMAN_WEAPON_EMBERLIGHT_01_SPEC));
		ids.put(Role.SPEC_ELDER_MAUL, Set.of(AnimationID.HUMAN_ELDER_MAUL_SPEC));
		ids.put(Role.SPEC_DRAGON_WARHAMMER, Set.of(AnimationID.DRAGON_WARHAMMER_SA_PLAYER));
		ids.put(Role.SPEC_BANDOS_GODSWORD, Set.of(AnimationID.BGS_SPECIAL_PLAYER, AnimationID.BGS_SPECIAL_ORNATE_PLAYER));
		ids.put(Role.SPEC_ACCURSED_SCEPTRE, Set.of(AnimationID.HUMAN_SPECIAL_ACCURSED));
		ids.put(Role.SPEC_EYE_OF_AYAK, Set.of(AnimationID.HUMAN_EYE_OF_AYAK_SPECIAL));
		ids.put(Role.SPEC_SOULFLAME_HORN, Set.of(AnimationID.SOULFLAME_HORN_BLOW_01, AnimationID.SOULFLAME_HORN_BLOW_02,
			AnimationID.SOULFLAME_HORN_BLOW_03, AnimationID.SOULFLAME_HORN_BLOW_03_NO_FIRE));
		ids.put(Role.SPEC_SARADOMIN_GODSWORD, Set.of(AnimationID.SGS_SPECIAL_PLAYER, AnimationID.SGS_SPECIAL_ORNATE_PLAYER));

		ids.put(Role.YAMA_CAST_MAGIC, Set.of(SpotanimID.VFX_NPC_YAMA_MAGIC_FIRE_SPOTANIM01));
		ids.put(Role.YAMA_CAST_RANGED, Set.of(SpotanimID.VFX_NPC_YAMA_MAGIC_SHADOW_SPOTANIM01));
		ids.put(Role.IMPACT_MAGIC, Set.of(SpotanimID.VFX_PLAYER_YAMA_MAGIC_FIRE_IMPACT01));
		ids.put(Role.IMPACT_RANGED, Set.of(SpotanimID.VFX_PLAYER_YAMA_MAGIC_SHADOW_IMPACT01));
		ids.put(Role.CRASH_IMPACT, Set.of(SpotanimID.VFX_PLAYER_YAMA_FALLING_ROCK_IMPACT01));
		ids.put(Role.SHADOW_WAVE, Set.of(SpotanimID.VFX_SHADOW_WALL_SMALL, SpotanimID.VFX_SHADOW_WALL_01,
			SpotanimID.VFX_SHADOW_WALL_02, SpotanimID.VFX_SHADOW_WALL_03));
		ids.put(Role.FIRE_STREAK, Set.of(SpotanimID.VFX_FIRE_WALL_01, SpotanimID.VFX_FIRE_WALL_02, SpotanimID.VFX_FIRE_WALL_03));
		ids.put(Role.FIRE_ATTACK, Set.of(SpotanimID.VFX_YAMA_FLAMING_ROCK_SPOTANIM_01,
			SpotanimID.VFX_YAMA_FLAMING_ROCK_PROJECTILE_01, SpotanimID.VFX_YAMA_FLAMING_ROCK_IMPACT_01));
		ids.put(Role.METEOR_STRIKE, Set.of(SpotanimID.VFX_YAMA_METEOR_SPOTANIM01, SpotanimID.VFX_YAMA_METEOR_PROJECTILE_01,
			SpotanimID.VFX_YAMA_METEOR_PROJECTILE_02, SpotanimID.VFX_YAMA_METEOR_PROJECTILE_03));
		ids.put(Role.GLYPH_PROTECTION, Set.of(SpotanimID.VFX_YAMA_FIRE_IMMUNITY, SpotanimID.VFX_YAMA_SHADOW_IMMUNITY));
		ids.put(Role.PHASE_TRANSITION_GRAPHIC, Set.of(SpotanimID.VFX_YAMA_PORTAL_SHADOW_SPOTANIM01));
		ids.put(Role.FLARE_HEAL, Set.of(SpotanimID.VFX_VOIDFLARE_EXPLODE_YAMA_IMPACT_RED, SpotanimID.VFX_VOIDFLARE_EXPLODE_YAMA_IMPACT_BLUE));
		ids.put(Role.FLARE_HIT, Set.of(SpotanimID.VFX_VOIDFLARE_HUMAN_IMPACT_RED, SpotanimID.VFX_VOIDFLARE_HUMAN_IMPACT_BLUE));

		Map<Role, Set<String>> texts = Map.of(
			Role.PHASE_TRANSITION_TEXT, Set.of("Begone", "You bore me.", "Enough."),
			Role.PRAYER_DISABLED_MESSAGE, Set.of("You've been injured and can't use protection prayers!"),
			Role.GLYPH_CONJURE_MESSAGE, Set.of("Yama conjures"));

		return new IdRegistry(ids, texts);
	}
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.yamareviewer.domain.ids.*' --tests 'com.yamareviewer.adapter.ids.*'`
Expected: PASS (10 tests). If `noIdBelongsToTwoRolesOfTheSameKind` fails, two roles share a constant: fix the mapping, never the test.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/yamareviewer/domain/ids src/main/java/com/yamareviewer/adapter/ids src/test/java/com/yamareviewer/domain/ids src/test/java/com/yamareviewer/adapter/ids
git commit -m "feat: add roles, tunables and built-in gameval IDs"
```

---

### Task 4: KillSession, the write side

**Files:**
- Create: `src/main/java/com/yamareviewer/application/port/SnapshotSource.java`
- Create: `src/main/java/com/yamareviewer/application/command/KillEndedListener.java`, `SessionState.java`, `FightStart.java`, `KillSession.java`
- Test: `src/test/java/com/yamareviewer/application/command/KillSessionTest.java`

**Interfaces:**
- Consumes: Task 2 events, `KillLog`, `KillHeader`.
- Produces: `SnapshotSource { Optional<SuppliesSnapshot> take(int tick, SnapshotKind kind); }`; `KillEndedListener { void killEnded(KillLog kill); }`; `FightStart(String selfName, Position selfPosition, List<String> playersPresent)`; `KillSession(KillEndedListener, SnapshotSource, Clock, Supplier<String> killIds, String pluginVersion, String idsFingerprint, BooleanSupplier captureMode)` with commands `chooseEntry(EntryChoice)`, `updateRegion(boolean)`, `yamaSpawned(FightStart)`, `playerSeen(String)`, `playerLeft(String)`, `record(DomainEvent)`, `endTick()`, `yamaDied()`, `playerDied()`, `leave()` and queries `state()`, `isFighting()`, `currentTick()`, `partnerName()`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/yamareviewer/application/command/KillSessionTest.java`:

```java
package com.yamareviewer.application.command;

import com.yamareviewer.application.port.SnapshotSource;
import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.EntryChoice;
import com.yamareviewer.domain.event.EntryChosen;
import com.yamareviewer.domain.event.EventType;
import com.yamareviewer.domain.event.FightEnded;
import com.yamareviewer.domain.event.FightStarted;
import com.yamareviewer.domain.event.HitsplatKind;
import com.yamareviewer.domain.event.HitsplatObserved;
import com.yamareviewer.domain.event.PlayerSeen;
import com.yamareviewer.domain.event.Position;
import com.yamareviewer.domain.event.SnapshotKind;
import com.yamareviewer.domain.event.SuppliesSnapshot;
import com.yamareviewer.domain.event.TickState;
import com.yamareviewer.domain.event.VarbitObserved;
import com.yamareviewer.domain.model.KillLog;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

public class KillSessionTest
{
	private final List<KillLog> ended = new ArrayList<>();
	private final FakeSnapshots snapshots = new FakeSnapshots();
	private final AtomicInteger killCounter = new AtomicInteger();
	private boolean capture;
	private KillSession session;

	@Before
	public void setUp()
	{
		Clock clock = Clock.fixed(Instant.ofEpochMilli(5_000), ZoneOffset.UTC);
		session = new KillSession(ended::add, snapshots, clock, () -> "kill-" + killCounter.incrementAndGet(),
			"0.1.0", "f00d", () -> capture);
	}

	private static FightStart solo()
	{
		return new FightStart("Me", new Position(1, 2, 0), List.of());
	}

	private static TickState state(int tick)
	{
		return new TickState(tick, Set.of(), 99, 99, 100, 100, -1, null, null, null);
	}

	private static List<String> types(KillLog kill)
	{
		return kill.getEvents().stream().map(e -> EventType.of(e).typeName()).collect(toList());
	}

	private void armAndStart(FightStart start)
	{
		session.updateRegion(true);
		session.yamaSpawned(start);
	}

	@Test
	public void recordsNothingOutsideTheRegion()
	{
		session.yamaSpawned(solo());
		session.record(new VarbitObserved(0, 1, 1));
		session.yamaDied();
		session.endTick();

		assertEquals(SessionState.IDLE, session.state());
		assertTrue(ended.isEmpty());
	}

	@Test
	public void fullKillProducesAnOrderedLog()
	{
		session.chooseEntry(EntryChoice.TRAVEL);
		armAndStart(solo());
		session.record(new AnimationObserved(0, Actor.YAMA, 10));
		session.record(state(0));
		session.endTick();
		session.record(new HitsplatObserved(1, Actor.SELF, HitsplatKind.DAMAGE, 5, 1, false));
		session.yamaDied();
		session.record(state(1));
		session.endTick();

		assertEquals(1, ended.size());
		KillLog kill = ended.get(0);
		assertEquals(List.of("entry", "fight-start", "supplies", "animation", "tick", "hitsplat", "supplies", "tick", "fight-end"),
			types(kill));
		assertEquals("kill-1", kill.getHeader().getKillId());
		assertEquals("f00d", kill.getHeader().getIdsFingerprint());
		assertEquals(5_000L, kill.getHeader().getStartEpochMs());
		assertEquals(EndReason.YAMA_DIED, kill.endReason());
		assertEquals(1, kill.lastTick());
		assertEquals(SessionState.ARMED, session.state());
	}

	@Test
	public void deathKeepsTheDeathTicksState()
	{
		armAndStart(solo());
		session.endTick();
		session.playerDied();

		assertTrue(ended.isEmpty());
		session.record(state(1));
		session.endTick();

		List<DomainEvent> events = ended.get(0).getEvents();
		assertTrue(events.get(events.size() - 2) instanceof TickState);
		assertEquals(new FightEnded(1, EndReason.PLAYER_DIED), events.get(events.size() - 1));
	}

	@Test
	public void theEndSnapshotIsTakenWhenTheEndIsSeen()
	{
		armAndStart(solo());
		session.playerDied();
		session.record(state(0));
		session.endTick();

		List<DomainEvent> events = ended.get(0).getEvents();
		SuppliesSnapshot end = (SuppliesSnapshot) events.get(events.size() - 3);
		assertEquals(SnapshotKind.END, end.getKind());
	}

	@Test
	public void leavingMidFightEndsAsLeftAtOnceAndForgetsTheEntryChoice()
	{
		session.chooseEntry(EntryChoice.JOIN);
		armAndStart(solo());
		session.updateRegion(false);

		assertEquals(EndReason.LEFT, ended.get(0).endReason());
		assertEquals(EntryChoice.JOIN, ended.get(0).eventsOf(EntryChosen.class).get(0).getChoice());
		assertEquals(SessionState.IDLE, session.state());

		armAndStart(solo());
		session.leave();
		assertTrue(ended.get(1).eventsOf(EntryChosen.class).isEmpty());
	}

	@Test
	public void leaveAfterDeathKeepsTheDeathReason()
	{
		armAndStart(solo());
		session.playerDied();
		session.leave();

		assertEquals(EndReason.PLAYER_DIED, ended.get(0).endReason());
	}

	@Test
	public void endSnapshotIsSkippedWhenUnavailable()
	{
		armAndStart(solo());
		snapshots.available = false;
		session.leave();

		assertEquals(List.of("fight-start", "supplies", "fight-end"), types(ended.get(0)));
	}

	@Test
	public void secondKillInSameInstanceGetsAFreshIdAndTicks()
	{
		armAndStart(solo());
		session.endTick();
		session.endTick();
		session.yamaDied();
		session.endTick();

		session.yamaSpawned(solo());
		session.endTick();
		session.yamaDied();
		session.endTick();

		assertEquals(2, ended.size());
		assertEquals("kill-2", ended.get(1).getHeader().getKillId());
		assertEquals(0, ended.get(1).eventsOf(FightStarted.class).get(0).getTick());
		assertEquals(1, ended.get(1).lastTick());
	}

	@Test
	public void spawnWhileFightingIsIgnored()
	{
		armAndStart(solo());
		session.endTick();
		session.yamaSpawned(new FightStart("Me", new Position(9, 9, 0), List.of("Someone")));
		session.leave();

		assertEquals(1, ended.get(0).eventsOf(FightStarted.class).size());
		assertTrue(ended.get(0).eventsOf(PlayerSeen.class).isEmpty());
	}

	@Test
	public void playersPresentOrArrivingAreSeenAndTheFirstIsThePartner()
	{
		capture = true;
		armAndStart(new FightStart("Me", new Position(1, 2, 0), List.of("Buddy")));
		session.endTick();
		session.playerSeen("Late");
		session.playerLeft("Late");

		assertEquals(Optional.of("Buddy"), session.partnerName());
		session.leave();
		KillLog kill = ended.get(0);
		assertEquals(List.of(new PlayerSeen(0, "Buddy"), new PlayerSeen(1, "Late")), kill.eventsOf(PlayerSeen.class));
		assertTrue(kill.getHeader().isCapture());
	}

	@Test
	public void entryChoiceDuringAFightIsIgnored()
	{
		session.chooseEntry(EntryChoice.TRAVEL);
		armAndStart(solo());
		session.chooseEntry(EntryChoice.JOIN);
		session.leave();

		assertEquals(EntryChoice.TRAVEL, ended.get(0).eventsOf(EntryChosen.class).get(0).getChoice());
	}

	private static final class FakeSnapshots implements SnapshotSource
	{
		private boolean available = true;

		@Override
		public Optional<SuppliesSnapshot> take(int tick, SnapshotKind kind)
		{
			return available ? Optional.of(new SuppliesSnapshot(tick, kind, List.of())) : Optional.empty();
		}
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'com.yamareviewer.application.command.KillSessionTest'`
Expected: FAIL — `cannot find symbol` for `KillSession`, `SnapshotSource`, `FightStart`.

- [ ] **Step 3: Write the port, listener and value types**

`src/main/java/com/yamareviewer/application/port/SnapshotSource.java`:

```java
package com.yamareviewer.application.port;

import com.yamareviewer.domain.event.SnapshotKind;
import com.yamareviewer.domain.event.SuppliesSnapshot;
import java.util.Optional;

/** Reads the player's supplies. Empty when they can't be read reliably (logged out, hopping). */
public interface SnapshotSource
{
	Optional<SuppliesSnapshot> take(int tick, SnapshotKind kind);
}
```

`src/main/java/com/yamareviewer/application/command/KillEndedListener.java`:

```java
package com.yamareviewer.application.command;

import com.yamareviewer.domain.model.KillLog;

public interface KillEndedListener
{
	/** Called on the client thread; implementations must hand real work to another thread. */
	void killEnded(KillLog kill);
}
```

`src/main/java/com/yamareviewer/application/command/SessionState.java`:

```java
package com.yamareviewer.application.command;

public enum SessionState
{
	IDLE,
	ARMED,
	FIGHTING
}
```

`src/main/java/com/yamareviewer/application/command/FightStart.java`:

```java
package com.yamareviewer.application.command;

import com.yamareviewer.domain.event.Position;
import java.util.List;
import lombok.Value;

@Value
public class FightStart
{
	String selfName;
	Position selfPosition;
	/** Other players already in the arena when Yama spawns. */
	List<String> playersPresent;
}
```

- [ ] **Step 4: Write `KillSession`**

`src/main/java/com/yamareviewer/application/command/KillSession.java`:

```java
package com.yamareviewer.application.command;

import com.yamareviewer.application.port.SnapshotSource;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.EntryChoice;
import com.yamareviewer.domain.event.EntryChosen;
import com.yamareviewer.domain.event.FightEnded;
import com.yamareviewer.domain.event.FightStarted;
import com.yamareviewer.domain.event.PlayerLeft;
import com.yamareviewer.domain.event.PlayerSeen;
import com.yamareviewer.domain.event.SnapshotKind;
import com.yamareviewer.domain.model.KillHeader;
import com.yamareviewer.domain.model.KillLog;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The write side. Its public methods are the commands; the only thing it produces is a frozen
 * {@link KillLog}, handed to the {@link KillEndedListener} when the kill ends.
 * Not thread-safe: call it from the client thread only.
 */
public final class KillSession
{
	private final KillEndedListener listener;
	private final SnapshotSource snapshots;
	private final Clock clock;
	private final Supplier<String> killIds;
	private final String pluginVersion;
	private final String idsFingerprint;
	private final BooleanSupplier captureMode;

	private final List<DomainEvent> events = new ArrayList<>();
	private SessionState state = SessionState.IDLE;
	private EntryChoice entryChoice;
	private int tick;
	private long startEpochMs;
	private String partnerName;
	private boolean capture;
	private EndReason pendingEnd;

	public KillSession(KillEndedListener listener, SnapshotSource snapshots, Clock clock, Supplier<String> killIds,
		String pluginVersion, String idsFingerprint, BooleanSupplier captureMode)
	{
		this.listener = listener;
		this.snapshots = snapshots;
		this.clock = clock;
		this.killIds = killIds;
		this.pluginVersion = pluginVersion;
		this.idsFingerprint = idsFingerprint;
		this.captureMode = captureMode;
	}

	public SessionState state()
	{
		return state;
	}

	public boolean isFighting()
	{
		return state == SessionState.FIGHTING;
	}

	public int currentTick()
	{
		return tick;
	}

	/** The first other player seen in this fight; used to label actors while recording. */
	public Optional<String> partnerName()
	{
		return Optional.ofNullable(partnerName);
	}

	/** Travel or Join at the Voice of Yama. Remembered until the player leaves Yama's Domain. */
	public void chooseEntry(EntryChoice choice)
	{
		if (state != SessionState.FIGHTING)
		{
			entryChoice = choice;
		}
	}

	public void updateRegion(boolean inYamasDomain)
	{
		if (inYamasDomain && state == SessionState.IDLE)
		{
			state = SessionState.ARMED;
		}
		else if (!inYamasDomain && state != SessionState.IDLE)
		{
			leave();
		}
	}

	public void yamaSpawned(FightStart start)
	{
		if (state != SessionState.ARMED)
		{
			return;
		}
		state = SessionState.FIGHTING;
		tick = 0;
		events.clear();
		pendingEnd = null;
		partnerName = null;
		startEpochMs = clock.millis();
		capture = captureMode.getAsBoolean();
		if (entryChoice != null)
		{
			events.add(new EntryChosen(0, entryChoice));
		}
		events.add(new FightStarted(0, start.getSelfName(), start.getSelfPosition()));
		start.getPlayersPresent().forEach(this::playerSeen);
		snapshots.take(0, SnapshotKind.START).ifPresent(events::add);
	}

	public void playerSeen(String name)
	{
		if (state != SessionState.FIGHTING)
		{
			return;
		}
		if (partnerName == null)
		{
			partnerName = name;
		}
		events.add(new PlayerSeen(tick, name));
	}

	public void playerLeft(String name)
	{
		if (state == SessionState.FIGHTING)
		{
			events.add(new PlayerLeft(tick, name));
		}
	}

	public void record(DomainEvent event)
	{
		if (state == SessionState.FIGHTING)
		{
			events.add(event);
		}
	}

	/** Called after the tick's TickState is recorded. Completes a latched end, otherwise advances the tick. */
	public void endTick()
	{
		if (state != SessionState.FIGHTING)
		{
			return;
		}
		if (pendingEnd != null)
		{
			complete(pendingEnd);
		}
		else
		{
			tick++;
		}
	}

	public void yamaDied()
	{
		latch(EndReason.YAMA_DIED);
	}

	public void playerDied()
	{
		latch(EndReason.PLAYER_DIED);
	}

	/** Logout, world hop, leaving the region or plugin shutdown: no further tick will come. */
	public void leave()
	{
		if (state == SessionState.FIGHTING)
		{
			if (pendingEnd == null)
			{
				snapshots.take(tick, SnapshotKind.END).ifPresent(events::add);
			}
			complete(pendingEnd != null ? pendingEnd : EndReason.LEFT);
		}
		state = SessionState.IDLE;
		entryChoice = null;
	}

	private void latch(EndReason reason)
	{
		if (state == SessionState.FIGHTING && pendingEnd == null)
		{
			pendingEnd = reason;
			snapshots.take(tick, SnapshotKind.END).ifPresent(events::add);
		}
	}

	private void complete(EndReason reason)
	{
		events.add(new FightEnded(tick, reason));
		KillHeader header = new KillHeader(killIds.get(), startEpochMs, clock.millis(), pluginVersion,
			KillLog.SCHEMA_VERSION, idsFingerprint, capture);
		KillLog kill = KillLog.of(header, events, 0);
		events.clear();
		partnerName = null;
		pendingEnd = null;
		state = SessionState.ARMED;
		listener.killEnded(kill);
	}
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests 'com.yamareviewer.application.command.KillSessionTest'`
Expected: PASS (11 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/yamareviewer/application src/test/java/com/yamareviewer/application
git commit -m "feat: add the KillSession write side"
```

---

### Task 5: Event codec and raw log storage

**Files:**
- Create: `src/main/java/com/yamareviewer/application/port/LogRepository.java`
- Create: `src/main/java/com/yamareviewer/adapter/persistence/FileStore.java`, `FilepathFileStore.java`, `EventCodec.java`, `GsonLogRepository.java`
- Test: `src/test/java/com/yamareviewer/adapter/persistence/InMemoryFileStore.java`, `FilepathFileStoreTest.java`, `EventCodecTest.java`, `GsonLogRepositoryTest.java`

**Interfaces:**
- Consumes: Task 2 events, `EventType`, `KillLog`, `KillHeader`.
- Produces: `LogRepository { void save(KillLog); List<KillLog> loadAll(); void prune(int keep); }` (all `throws IOException`, `loadAll` newest first, current schema only); `FileStore` (relative `/`-separated paths); `FilepathFileStore(Filepath)`; `EventCodec(Gson)` with `encode`, `decode`, `encodeHeader`, `decodeHeader`; `GsonLogRepository(FileStore, EventCodec)` with package-private `fileName(KillHeader)` and `gzip(byte[])`.

- [ ] **Step 1: Write the test helper and failing tests**

`src/test/java/com/yamareviewer/adapter/persistence/InMemoryFileStore.java`:

```java
package com.yamareviewer.adapter.persistence;

import java.io.FileNotFoundException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

public final class InMemoryFileStore implements FileStore
{
	private final Map<String, byte[]> files = new TreeMap<>();

	@Override
	public void write(String path, byte[] data)
	{
		files.put(path, data.clone());
	}

	@Override
	public byte[] read(String path) throws FileNotFoundException
	{
		byte[] data = files.get(path);
		if (data == null)
		{
			throw new FileNotFoundException(path);
		}
		return data.clone();
	}

	@Override
	public List<String> list(String dir)
	{
		String prefix = dir + "/";
		return files.keySet().stream()
			.filter(path -> path.startsWith(prefix) && path.indexOf('/', prefix.length()) < 0)
			.map(path -> path.substring(prefix.length()))
			.sorted()
			.collect(Collectors.toList());
	}

	@Override
	public void delete(String path)
	{
		files.remove(path);
	}

	@Override
	public void rename(String from, String to) throws FileNotFoundException
	{
		byte[] data = files.remove(from);
		if (data == null)
		{
			throw new FileNotFoundException(from);
		}
		files.put(to, data);
	}

	public Set<String> paths()
	{
		return files.keySet();
	}
}
```

`src/test/java/com/yamareviewer/adapter/persistence/FilepathFileStoreTest.java`:

```java
package com.yamareviewer.adapter.persistence;

import java.io.IOException;
import java.util.List;
import net.runelite.client.util.Filepath;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FilepathFileStoreTest
{
	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private FilepathFileStore store;

	@Before
	public void setUp()
	{
		// Filepath.Unchecked is fine in tests: the Plugin Hub never compiles them.
		store = new FilepathFileStore(Filepath.Unchecked.getRooted(folder.getRoot().toPath()));
	}

	@Test
	public void writesReadsListsRenamesAndDeletes() throws IOException
	{
		store.write("raw/b.jsonl.gz", new byte[]{2});
		store.write("raw/a.jsonl.gz", new byte[]{1});

		assertEquals(List.of("a.jsonl.gz", "b.jsonl.gz"), store.list("raw"));
		assertArrayEquals(new byte[]{1}, store.read("raw/a.jsonl.gz"));

		store.rename("raw/a.jsonl.gz", "raw/a.jsonl.gz.corrupt");
		store.delete("raw/b.jsonl.gz");

		assertEquals(List.of("a.jsonl.gz.corrupt"), store.list("raw"));
	}

	@Test
	public void listingAMissingDirectoryIsEmpty() throws IOException
	{
		assertEquals(List.of(), store.list("reviews"));
	}

	@Test(expected = IllegalArgumentException.class)
	public void pathsCannotEscapeTheRoot() throws IOException
	{
		store.write("../outside.txt", new byte[]{1});
	}
}
```

`src/test/java/com/yamareviewer/adapter/persistence/EventCodecTest.java`:

```java
package com.yamareviewer.adapter.persistence;

import com.google.gson.Gson;
import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.EntryChoice;
import com.yamareviewer.domain.event.EntryChosen;
import com.yamareviewer.domain.event.EventType;
import com.yamareviewer.domain.event.FightEnded;
import com.yamareviewer.domain.event.FightStarted;
import com.yamareviewer.domain.event.GameMessageObserved;
import com.yamareviewer.domain.event.GameStateKind;
import com.yamareviewer.domain.event.GameStateObserved;
import com.yamareviewer.domain.event.GraphicObserved;
import com.yamareviewer.domain.event.GroundGraphicObserved;
import com.yamareviewer.domain.event.HitsplatKind;
import com.yamareviewer.domain.event.HitsplatObserved;
import com.yamareviewer.domain.event.InventoryDelta;
import com.yamareviewer.domain.event.NpcChangedObserved;
import com.yamareviewer.domain.event.NpcDespawnObserved;
import com.yamareviewer.domain.event.NpcSpawnObserved;
import com.yamareviewer.domain.event.ObjectAnimationObserved;
import com.yamareviewer.domain.event.ObjectDespawnObserved;
import com.yamareviewer.domain.event.ObjectSpawnObserved;
import com.yamareviewer.domain.event.OverheadTextObserved;
import com.yamareviewer.domain.event.PlayerLeft;
import com.yamareviewer.domain.event.PlayerSeen;
import com.yamareviewer.domain.event.Position;
import com.yamareviewer.domain.event.ProjectileObserved;
import com.yamareviewer.domain.event.ProtectionPrayer;
import com.yamareviewer.domain.event.SnapshotKind;
import com.yamareviewer.domain.event.SuppliesSnapshot;
import com.yamareviewer.domain.event.SupplyItem;
import com.yamareviewer.domain.event.TickState;
import com.yamareviewer.domain.event.VarbitObserved;
import com.yamareviewer.domain.event.WidgetTextObserved;
import com.yamareviewer.domain.model.KillHeader;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class EventCodecTest
{
	private final EventCodec codec = new EventCodec(new Gson());

	static List<DomainEvent> oneOfEach()
	{
		Position here = new Position(3200, 3200, 0);
		return List.of(
			new EntryChosen(0, EntryChoice.JOIN),
			new FightStarted(0, "Me", here),
			new FightEnded(90, EndReason.PLAYER_DIED),
			new PlayerSeen(0, "Buddy"),
			new PlayerLeft(80, "Buddy"),
			new GameStateObserved(3, GameStateKind.LOADING),
			new NpcSpawnObserved(1, Actor.flare(7), 1234, 7),
			new NpcDespawnObserved(2, Actor.YAMA, 1235, 1, true),
			new NpcChangedObserved(2, Actor.YAMA, 1235, 1236),
			new ObjectSpawnObserved(3, 555, here),
			new ObjectDespawnObserved(4, 555, here),
			new ObjectAnimationObserved(4, 555, here, 12169),
			new OverheadTextObserved(4, Actor.YAMA, "Enough."),
			new GameMessageObserved(4, "<col=ef1020>You've been injured and can't use protection prayers!</col>"),
			new VarbitObserved(5, 16550, 2),
			new WidgetTextObserved(5, 59899907, "Contract of Bloodied Blows"),
			new AnimationObserved(6, Actor.PARTNER, 42),
			new GraphicObserved(7, Actor.SELF, 43),
			new GroundGraphicObserved(8, 44, here),
			new ProjectileObserved(9, 45, Actor.SELF, 11),
			new HitsplatObserved(10, Actor.JUDGE, HitsplatKind.BLOCK, 0, 12, true),
			new TickState(11, Set.of(ProtectionPrayer.MAGIC), 80, 50, 100, 64, 4151, Actor.SELF, here, null),
			new InventoryDelta(12, 385, "Shark", -1),
			new SuppliesSnapshot(13, SnapshotKind.END, List.of(new SupplyItem(385, "Shark", 3, 800, 90))));
	}

	@Test
	public void roundTripsEveryEventType()
	{
		for (DomainEvent event : oneOfEach())
		{
			assertEquals(Optional.of(event), codec.decode(codec.encode(event)));
		}
	}

	@Test
	public void theRoundTripCoversEveryEventType()
	{
		Set<EventType> covered = oneOfEach().stream().map(EventType::of).collect(Collectors.toSet());
		assertEquals(Set.copyOf(Arrays.asList(EventType.values())), covered);
	}

	@Test
	public void usesTheStableTypeName()
	{
		assertTrue(codec.encode(new VarbitObserved(1, 2, 3)).startsWith("{\"type\":\"varbit\""));
	}

	@Test
	public void unknownTypeDecodesToEmpty()
	{
		assertEquals(Optional.empty(), codec.decode("{\"type\":\"from-the-future\",\"data\":{\"tick\":1}}"));
	}

	@Test
	public void headerRoundTrips()
	{
		KillHeader header = new KillHeader("k", 1L, 2L, "0.1.0", 1, "f00d", true);
		assertEquals(header, codec.decodeHeader(codec.encodeHeader(header)));
	}
}
```

`src/test/java/com/yamareviewer/adapter/persistence/GsonLogRepositoryTest.java`:

```java
package com.yamareviewer.adapter.persistence;

import com.google.gson.Gson;
import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.FightEnded;
import com.yamareviewer.domain.model.KillHeader;
import com.yamareviewer.domain.model.KillLog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class GsonLogRepositoryTest
{
	private final InMemoryFileStore files = new InMemoryFileStore();
	private final EventCodec codec = new EventCodec(new Gson());
	private final GsonLogRepository repository = new GsonLogRepository(files, codec);

	private static KillLog kill(String id, long start, int schemaVersion)
	{
		KillHeader header = new KillHeader(id, start, start + 60_000, "0.1.0", schemaVersion, "f00d", false);
		return KillLog.of(header, List.of(new AnimationObserved(0, Actor.YAMA, 1), new FightEnded(5, EndReason.YAMA_DIED)), 0);
	}

	private static KillLog kill(String id, long start)
	{
		return kill(id, start, KillLog.SCHEMA_VERSION);
	}

	private List<String> loadedIds() throws IOException
	{
		return repository.loadAll().stream().map(k -> k.getHeader().getKillId()).collect(toList());
	}

	@Test
	public void savedLogLoadsBackEqual() throws IOException
	{
		KillLog kill = kill("a", 1_000);

		repository.save(kill);

		assertEquals(List.of(kill), repository.loadAll());
	}

	@Test
	public void loadsNewestFirst() throws IOException
	{
		repository.save(kill("old", 1_000));
		repository.save(kill("new", 2_000));

		assertEquals(List.of("new", "old"), loadedIds());
	}

	@Test
	public void pruneKeepsTheNewest() throws IOException
	{
		repository.save(kill("1", 1_000));
		repository.save(kill("2", 2_000));
		repository.save(kill("3", 3_000));

		repository.prune(2);

		assertEquals(List.of("3", "2"), loadedIds());
	}

	@Test
	public void corruptFileIsRenamedAndSkipped() throws IOException
	{
		repository.save(kill("good", 2_000));
		files.write("raw/0000000001000-bad.jsonl.gz", new byte[]{1, 2, 3});

		assertEquals(List.of("good"), loadedIds());
		assertTrue(files.paths().contains("raw/0000000001000-bad.jsonl.gz.corrupt"));
		assertFalse(files.paths().contains("raw/0000000001000-bad.jsonl.gz"));
	}

	@Test
	public void otherSchemaVersionsAreSkippedButKept() throws IOException
	{
		repository.save(kill("current", 2_000));
		repository.save(kill("future", 3_000, KillLog.SCHEMA_VERSION + 1));

		assertEquals(List.of("current"), loadedIds());
		assertEquals(2, files.list("raw").size());
	}

	@Test
	public void unknownEventTypesAreCountedNotFatal() throws IOException
	{
		KillHeader header = kill("x", 1_000).getHeader();
		String text = codec.encodeHeader(header) + "\n"
			+ "{\"type\":\"from-the-future\",\"data\":{}}\n"
			+ codec.encode(new FightEnded(3, EndReason.LEFT)) + "\n";
		files.write("raw/" + GsonLogRepository.fileName(header), GsonLogRepository.gzip(text.getBytes(StandardCharsets.UTF_8)));

		KillLog loaded = repository.loadAll().get(0);

		assertEquals(1, loaded.getSkippedEvents());
		assertEquals(1, loaded.getEvents().size());
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'com.yamareviewer.adapter.persistence.*'`
Expected: FAIL — `cannot find symbol` for `FileStore`, `FilepathFileStore`, `EventCodec`, `GsonLogRepository`.

- [ ] **Step 3: Write the port and the file stores**

`src/main/java/com/yamareviewer/application/port/LogRepository.java`:

```java
package com.yamareviewer.application.port;

import com.yamareviewer.domain.model.KillLog;
import java.io.IOException;
import java.util.List;

/** Stores the write model: one raw event log per kill. */
public interface LogRepository
{
	void save(KillLog kill) throws IOException;

	/**
	 * Newest first, current schema version only. Unreadable files are renamed with a .corrupt suffix
	 * and skipped; logs of another schema version are skipped and left in place.
	 */
	List<KillLog> loadAll() throws IOException;

	/** Deletes the oldest raw logs so that at most {@code keep} remain. */
	void prune(int keep) throws IOException;
}
```

`src/main/java/com/yamareviewer/adapter/persistence/FileStore.java`:

```java
package com.yamareviewer.adapter.persistence;

import java.io.IOException;
import java.util.List;

/** Files under the plugin directory, addressed by relative "/"-separated paths. */
public interface FileStore
{
	/** Creates parent directories and replaces any existing file. */
	void write(String path, byte[] data) throws IOException;

	byte[] read(String path) throws IOException;

	/** Names of the files directly inside {@code dir}, ascending; empty when the directory doesn't exist. */
	List<String> list(String dir) throws IOException;

	void delete(String path) throws IOException;

	void rename(String from, String to) throws IOException;
}
```

`src/main/java/com/yamareviewer/adapter/persistence/FilepathFileStore.java`:

```java
package com.yamareviewer.adapter.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.runelite.client.util.Filepath;

/** The production FileStore, rooted at Plugin.getPluginDirectory(). */
public final class FilepathFileStore implements FileStore
{
	private final Filepath root;

	public FilepathFileStore(Filepath root)
	{
		this.root = root;
	}

	@Override
	public void write(String path, byte[] data) throws IOException
	{
		Filepath target = resolve(path);
		target.getParent().createDirectories();
		Filepath temp = resolve(path + ".tmp");
		temp.write(data);
		temp.moveTo(target, StandardCopyOption.REPLACE_EXISTING);
	}

	@Override
	public byte[] read(String path) throws IOException
	{
		try (InputStream in = resolve(path).openInputStream())
		{
			return in.readAllBytes();
		}
	}

	@Override
	public List<String> list(String dir) throws IOException
	{
		Filepath directory = resolve(dir);
		if (!directory.isDirectory())
		{
			return List.of();
		}
		try (Stream<Filepath> entries = directory.walk(1))
		{
			return entries
				.filter(entry -> !entry.equals(directory) && entry.isFile())
				.map(Filepath::getFileName)
				.sorted()
				.collect(Collectors.toList());
		}
	}

	@Override
	public void delete(String path) throws IOException
	{
		resolve(path).deleteIfExists();
	}

	@Override
	public void rename(String from, String to) throws IOException
	{
		resolve(from).moveTo(resolve(to), StandardCopyOption.REPLACE_EXISTING);
	}

	private Filepath resolve(String path)
	{
		String[] parts = path.split("/");
		return root.join(parts[0], Arrays.copyOfRange(parts, 1, parts.length));
	}
}
```

- [ ] **Step 4: Write `EventCodec`**

`src/main/java/com/yamareviewer/adapter/persistence/EventCodec.java`:

```java
package com.yamareviewer.adapter.persistence;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.event.EventType;
import com.yamareviewer.domain.model.KillHeader;
import java.util.Optional;

/** One JSON object per line: {"type": "<stable type name>", "data": {...}}. */
public final class EventCodec
{
	private final Gson gson;

	public EventCodec(Gson gson)
	{
		this.gson = gson;
	}

	public String encodeHeader(KillHeader header)
	{
		return gson.toJson(header);
	}

	public KillHeader decodeHeader(String line)
	{
		return gson.fromJson(line, KillHeader.class);
	}

	public String encode(DomainEvent event)
	{
		JsonObject wrapper = new JsonObject();
		wrapper.addProperty("type", EventType.of(event).typeName());
		wrapper.add("data", gson.toJsonTree(event));
		return gson.toJson(wrapper);
	}

	/** Empty for a type this version doesn't know. */
	public Optional<DomainEvent> decode(String line)
	{
		JsonObject wrapper = gson.fromJson(line, JsonObject.class);
		return EventType.byName(wrapper.get("type").getAsString())
			.<DomainEvent>map(type -> gson.fromJson(wrapper.get("data"), type.eventClass()));
	}
}
```

- [ ] **Step 5: Write `GsonLogRepository`**

`src/main/java/com/yamareviewer/adapter/persistence/GsonLogRepository.java`:

```java
package com.yamareviewer.adapter.persistence;

import com.yamareviewer.application.port.LogRepository;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.model.KillHeader;
import com.yamareviewer.domain.model.KillLog;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import lombok.extern.slf4j.Slf4j;

/** raw/<startEpochMs, 13 digits>-<killId>.jsonl.gz: line 1 the header, then one event per line. */
@Slf4j
public final class GsonLogRepository implements LogRepository
{
	private static final String RAW = "raw";
	private static final String SUFFIX = ".jsonl.gz";

	private final FileStore files;
	private final EventCodec codec;

	public GsonLogRepository(FileStore files, EventCodec codec)
	{
		this.files = files;
		this.codec = codec;
	}

	@Override
	public void save(KillLog kill) throws IOException
	{
		files.write(RAW + "/" + fileName(kill.getHeader()), gzip(lines(kill).getBytes(StandardCharsets.UTF_8)));
	}

	@Override
	public List<KillLog> loadAll() throws IOException
	{
		List<String> names = logFileNames();
		List<KillLog> kills = new ArrayList<>();
		for (int i = names.size() - 1; i >= 0; i--)
		{
			String path = RAW + "/" + names.get(i);
			try
			{
				parse(new String(gunzip(files.read(path)), StandardCharsets.UTF_8)).ifPresent(kills::add);
			}
			catch (IOException | RuntimeException e)
			{
				log.warn("Unreadable raw log {}, renaming it to .corrupt", path, e);
				files.rename(path, path + ".corrupt");
			}
		}
		return kills;
	}

	@Override
	public void prune(int keep) throws IOException
	{
		List<String> names = logFileNames();
		for (int i = 0; i < names.size() - keep; i++)
		{
			files.delete(RAW + "/" + names.get(i));
		}
	}

	static String fileName(KillHeader header)
	{
		return String.format("%013d-%s%s", header.getStartEpochMs(), header.getKillId(), SUFFIX);
	}

	static byte[] gzip(byte[] data) throws IOException
	{
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (OutputStream out = new GZIPOutputStream(bytes))
		{
			out.write(data);
		}
		return bytes.toByteArray();
	}

	private static byte[] gunzip(byte[] data) throws IOException
	{
		try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(data)))
		{
			return in.readAllBytes();
		}
	}

	/** Empty for a log of another schema version. */
	private Optional<KillLog> parse(String text)
	{
		String[] lines = text.split("\n");
		KillHeader header = codec.decodeHeader(lines[0]);
		if (header.getSchemaVersion() != KillLog.SCHEMA_VERSION)
		{
			return Optional.empty();
		}
		List<DomainEvent> events = new ArrayList<>();
		int skipped = 0;
		for (int i = 1; i < lines.length; i++)
		{
			if (lines[i].isBlank())
			{
				continue;
			}
			Optional<DomainEvent> event = codec.decode(lines[i]);
			if (event.isPresent())
			{
				events.add(event.get());
			}
			else
			{
				skipped++;
			}
		}
		return Optional.of(KillLog.of(header, events, skipped));
	}

	private String lines(KillLog kill)
	{
		StringBuilder text = new StringBuilder(codec.encodeHeader(kill.getHeader())).append('\n');
		for (DomainEvent event : kill.getEvents())
		{
			text.append(codec.encode(event)).append('\n');
		}
		return text.toString();
	}

	private List<String> logFileNames() throws IOException
	{
		return files.list(RAW).stream().filter(name -> name.endsWith(SUFFIX)).collect(Collectors.toList());
	}
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.yamareviewer.adapter.persistence.*'`
Expected: PASS (14 tests).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/yamareviewer/application/port/LogRepository.java src/main/java/com/yamareviewer/adapter/persistence src/test/java/com/yamareviewer/adapter/persistence
git commit -m "feat: store raw kill logs as gzipped JSON lines with stable type names"
```

---

### Task 6: KillEndedHandler on the plugin's own executor

**Files:**
- Create: `src/main/java/com/yamareviewer/application/handler/KillEndedHandler.java`
- Test: `src/test/java/com/yamareviewer/application/handler/KillEndedHandlerTest.java`

**Interfaces:**
- Consumes: `KillEndedListener` (Task 4), `LogRepository` (Task 5).
- Produces: `KillEndedHandler(ExecutorService executor, LogRepository logs, IntSupplier rawLogsKept)` implementing `KillEndedListener`; `Future<?> pending()` for `ClientShutdown`; package-private `handle(KillLog)`. Part 2 extends `handle` with review building and publishing.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/yamareviewer/application/handler/KillEndedHandlerTest.java`:

```java
package com.yamareviewer.application.handler;

import com.yamareviewer.application.port.LogRepository;
import com.yamareviewer.domain.model.KillHeader;
import com.yamareviewer.domain.model.KillLog;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.After;
import org.junit.Test;

public class KillEndedHandlerTest
{
	private final FakeLogs logs = new FakeLogs();
	private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "yama-reviewer-test"));

	@After
	public void tearDown()
	{
		executor.shutdownNow();
	}

	private static KillLog kill()
	{
		return KillLog.of(new KillHeader("k", 1L, 2L, "0.1.0", KillLog.SCHEMA_VERSION, "f00d", false), List.of(), 0);
	}

	@Test
	public void savesThenPrunesOnTheExecutor() throws Exception
	{
		KillEndedHandler handler = new KillEndedHandler(executor, logs, () -> 20);

		handler.killEnded(kill());
		handler.pending().get(5, TimeUnit.SECONDS);

		assertEquals(List.of("save k on yama-reviewer-test", "prune 20"), logs.calls);
	}

	@Test
	public void storageFailureDoesNotEscape() throws Exception
	{
		logs.fail = true;
		KillEndedHandler handler = new KillEndedHandler(executor, logs, () -> 20);

		handler.killEnded(kill());
		handler.pending().get(5, TimeUnit.SECONDS);

		assertTrue(logs.calls.isEmpty());
	}

	@Test
	public void aStoppedExecutorIsLoggedNotThrown()
	{
		executor.shutdownNow();
		KillEndedHandler handler = new KillEndedHandler(executor, logs, () -> 20);

		handler.killEnded(kill());

		assertTrue(handler.pending().isDone());
	}

	private static final class FakeLogs implements LogRepository
	{
		private final List<String> calls = new CopyOnWriteArrayList<>();
		private volatile boolean fail;

		@Override
		public void save(KillLog kill) throws IOException
		{
			if (fail)
			{
				throw new IOException("disk full");
			}
			calls.add("save " + kill.getHeader().getKillId() + " on " + Thread.currentThread().getName());
		}

		@Override
		public List<KillLog> loadAll()
		{
			return List.of();
		}

		@Override
		public void prune(int keep)
		{
			calls.add("prune " + keep);
		}
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'com.yamareviewer.application.handler.KillEndedHandlerTest'`
Expected: FAIL — `cannot find symbol: class KillEndedHandler`.

- [ ] **Step 3: Write `KillEndedHandler`**

`src/main/java/com/yamareviewer/application/handler/KillEndedHandler.java`:

```java
package com.yamareviewer.application.handler;

import com.yamareviewer.application.command.KillEndedListener;
import com.yamareviewer.application.port.LogRepository;
import com.yamareviewer.domain.model.KillLog;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.IntSupplier;
import lombok.extern.slf4j.Slf4j;

/** Everything that happens after a kill, on the plugin's own executor. Part 1: persist the raw log. */
@Slf4j
public final class KillEndedHandler implements KillEndedListener
{
	private final ExecutorService executor;
	private final LogRepository logs;
	private final IntSupplier rawLogsKept;
	private volatile Future<?> pending = CompletableFuture.completedFuture(null);

	public KillEndedHandler(ExecutorService executor, LogRepository logs, IntSupplier rawLogsKept)
	{
		this.executor = executor;
		this.logs = logs;
		this.rawLogsKept = rawLogsKept;
	}

	@Override
	public void killEnded(KillLog kill)
	{
		try
		{
			pending = executor.submit(() -> handle(kill));
		}
		catch (RejectedExecutionException e)
		{
			log.warn("Plugin stopped before kill {} could be stored", kill.getHeader().getKillId());
		}
	}

	/** The latest submitted work, so client shutdown can wait for it. */
	public Future<?> pending()
	{
		return pending;
	}

	void handle(KillLog kill)
	{
		try
		{
			logs.save(kill);
			logs.prune(Math.max(1, rawLogsKept.getAsInt()));
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Could not store raw log {}", kill.getHeader().getKillId(), e);
		}
	}
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'com.yamareviewer.application.handler.KillEndedHandlerTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yamareviewer/application/handler src/test/java/com/yamareviewer/application/handler
git commit -m "feat: save raw logs on the plugin's own executor when a kill ends"
```

---

### Task 7: Positions, actors and event translation

**Files:**
- Create: `src/main/java/com/yamareviewer/adapter/recording/PositionReader.java`, `ActorResolver.java`, `EventTranslator.java`
- Test: `src/test/java/com/yamareviewer/adapter/recording/ActorResolverTest.java`, `EventTranslatorTest.java`

**Interfaces:**
- Consumes: Task 2 events, Task 3 `IdRegistry`/`Role`/`RoleKind`.
- Produces: `PositionReader(Client)` with `Position position(LocalPoint)` and `int regionId(LocalPoint)` (both null-safe, non-final for mocking); `ActorResolver(Client, IdRegistry, Supplier<Optional<String>> partnerName)` with `Actor resolve(net.runelite.api.Actor)`; `EventTranslator(IdRegistry, ActorResolver, IntSupplier tick, BooleanSupplier capture, IntFunction<String> itemNames, PositionReader)` with `reset(Map<Integer,Integer>)`, one method per RuneLite event returning `List<DomainEvent>` (including `chatMessage`), `pollObjectAnimations()`, `widgetText(int, String)`, `gameState(GameState)`, `contractConsumed(int)`, and static `aggregate(Item[])`, `entryChoice(String)`.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/yamareviewer/adapter/recording/ActorResolverTest.java`:

```java
package com.yamareviewer.adapter.recording;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import static org.junit.Assert.assertEquals;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ActorResolverTest
{
	private final IdRegistry ids = new IdRegistry(
		Map.of(Role.YAMA, Set.of(100), Role.JUDGE, Set.of(101), Role.VOID_FLARE, Set.of(102), Role.METEOR_NPC, Set.of(103)),
		Map.of());
	private final Client client = mock(Client.class);
	private final Player self = mock(Player.class);
	private ActorResolver resolver;

	@Before
	public void setUp()
	{
		when(client.getLocalPlayer()).thenReturn(self);
		resolver = new ActorResolver(client, ids, () -> Optional.of("Buddy"));
	}

	private static NPC npc(int id, int index)
	{
		NPC npc = mock(NPC.class);
		when(npc.getId()).thenReturn(id);
		when(npc.getIndex()).thenReturn(index);
		return npc;
	}

	private static Player player(String name)
	{
		Player player = mock(Player.class);
		when(player.getName()).thenReturn(name);
		return player;
	}

	@Test
	public void resolvesPlayers()
	{
		assertEquals(Actor.SELF, resolver.resolve(self));
		assertEquals(Actor.PARTNER, resolver.resolve(player("Buddy")));
		assertEquals(Actor.other("Stranger"), resolver.resolve(player("Stranger")));
	}

	@Test
	public void resolvesNpcsByRole()
	{
		assertEquals(Actor.YAMA, resolver.resolve(npc(100, 1)));
		assertEquals(Actor.JUDGE, resolver.resolve(npc(101, 2)));
		assertEquals(Actor.flare(33), resolver.resolve(npc(102, 33)));
		assertEquals(Actor.npc(103), resolver.resolve(npc(103, 4)));
		assertEquals(Actor.other("npc 999"), resolver.resolve(npc(999, 5)));
	}

	@Test
	public void nullIsNobody()
	{
		assertEquals(Actor.other("none"), resolver.resolve(null));
	}
}
```

`src/test/java/com/yamareviewer/adapter/recording/EventTranslatorTest.java`:

```java
package com.yamareviewer.adapter.recording;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.event.EntryChoice;
import com.yamareviewer.domain.event.GameMessageObserved;
import com.yamareviewer.domain.event.GameStateKind;
import com.yamareviewer.domain.event.GameStateObserved;
import com.yamareviewer.domain.event.GraphicObserved;
import com.yamareviewer.domain.event.HitsplatKind;
import com.yamareviewer.domain.event.HitsplatObserved;
import com.yamareviewer.domain.event.InventoryDelta;
import com.yamareviewer.domain.event.ObjectAnimationObserved;
import com.yamareviewer.domain.event.Position;
import com.yamareviewer.domain.event.ProjectileObserved;
import com.yamareviewer.domain.event.VarbitObserved;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.runelite.api.ActorSpotAnim;
import net.runelite.api.Animation;
import net.runelite.api.DynamicObject;
import net.runelite.api.GameObject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameState;
import net.runelite.api.Hitsplat;
import net.runelite.api.HitsplatID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.IterableHashTable;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Projectile;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InventoryID;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class EventTranslatorTest
{
	private final IdRegistry ids = new IdRegistry(
		Map.of(Role.YAMA, Set.of(100), Role.PHASE_VARBIT, Set.of(16550), Role.GLYPH_FIRE, Set.of(500)),
		Map.of());
	private final ActorResolver actors = mock(ActorResolver.class);
	private final PositionReader positions = mock(PositionReader.class);
	private final NPC yama = mock(NPC.class);
	private final Player stranger = mock(Player.class);
	private boolean capture;
	private EventTranslator translator;

	@Before
	public void setUp()
	{
		when(actors.resolve(yama)).thenReturn(Actor.YAMA);
		when(actors.resolve(stranger)).thenReturn(Actor.other("Stranger"));
		when(positions.position(any())).thenReturn(new Position(1, 2, 0));
		translator = new EventTranslator(ids, actors, () -> 12, () -> capture, id -> "Item " + id, positions);
	}

	private static AnimationChanged animation(net.runelite.api.Actor actor)
	{
		AnimationChanged event = new AnimationChanged();
		event.setActor(actor);
		return event;
	}

	@Test
	public void animationOfAKnownActorIsRecorded()
	{
		when(yama.getAnimation()).thenReturn(4242);

		assertEquals(List.of(new AnimationObserved(12, Actor.YAMA, 4242)), translator.animationChanged(animation(yama)));
	}

	@Test
	public void idleAnimationIsSkipped()
	{
		when(yama.getAnimation()).thenReturn(-1);

		assertTrue(translator.animationChanged(animation(yama)).isEmpty());
	}

	@Test
	public void otherActorsAreDroppedUnlessCapturing()
	{
		when(stranger.getAnimation()).thenReturn(808);

		assertTrue(translator.animationChanged(animation(stranger)).isEmpty());
		capture = true;
		assertEquals(1, translator.animationChanged(animation(stranger)).size());
	}

	@Test
	public void hitsplatKeepsKindAmountAndOwnership()
	{
		Hitsplat hitsplat = mock(Hitsplat.class);
		when(hitsplat.getHitsplatType()).thenReturn(HitsplatID.BLOCK_ME);
		when(hitsplat.getAmount()).thenReturn(0);
		when(hitsplat.isMine()).thenReturn(true);
		HitsplatApplied event = new HitsplatApplied();
		event.setActor(yama);
		event.setHitsplat(hitsplat);

		assertEquals(List.of(new HitsplatObserved(12, Actor.YAMA, HitsplatKind.BLOCK, 0, HitsplatID.BLOCK_ME, true)),
			translator.hitsplatApplied(event));
	}

	@Test
	public void onlySystemMessagesAreRecorded()
	{
		ChatMessage system = new ChatMessage();
		system.setType(ChatMessageType.GAMEMESSAGE);
		system.setMessage("You absorb a Glyph of Fire.");
		ChatMessage chat = new ChatMessage();
		chat.setType(ChatMessageType.PUBLICCHAT);
		chat.setMessage("hello");

		assertEquals(List.of(new GameMessageObserved(12, "You absorb a Glyph of Fire.")), translator.chatMessage(system));
		assertTrue(translator.chatMessage(chat).isEmpty());
	}

	@Test
	public void onlyRoleVarbitsAreRecorded()
	{
		VarbitChanged phase = new VarbitChanged();
		phase.setVarbitId(16550);
		phase.setValue(2);
		VarbitChanged other = new VarbitChanged();
		other.setVarbitId(1);
		other.setValue(1);

		assertEquals(List.of(new VarbitObserved(12, 16550, 2)), translator.varbitChanged(phase));
		assertTrue(translator.varbitChanged(other).isEmpty());
	}

	@Test
	@SuppressWarnings("unchecked")
	public void eachSpotAnimIsRecordedOnce()
	{
		ActorSpotAnim spotAnim = mock(ActorSpotAnim.class);
		when(spotAnim.getId()).thenReturn(3247);
		when(spotAnim.getStartCycle()).thenReturn(500);
		IterableHashTable<ActorSpotAnim> table = mock(IterableHashTable.class);
		when(table.iterator()).thenAnswer(invocation -> List.of(spotAnim).iterator());
		when(yama.getSpotAnims()).thenReturn(table);
		GraphicChanged event = new GraphicChanged();
		event.setActor(yama);

		assertEquals(List.of(new GraphicObserved(12, Actor.YAMA, 3247)), translator.graphicChanged(event));
		assertTrue(translator.graphicChanged(event).isEmpty());
	}

	@Test
	public void projectileIsRecordedOnFirstSightingWithItsLandingTick()
	{
		Projectile projectile = mock(Projectile.class);
		when(projectile.getId()).thenReturn(77);
		when(projectile.getRemainingCycles()).thenReturn(45);
		when(projectile.getTargetActor()).thenReturn(yama);
		ProjectileMoved event = new ProjectileMoved();
		event.setProjectile(projectile);

		assertEquals(List.of(new ProjectileObserved(12, 77, Actor.YAMA, 14)), translator.projectileMoved(event));
		assertTrue(translator.projectileMoved(event).isEmpty());
	}

	@Test
	public void glyphAnimationsArePolledOncePerChange()
	{
		Animation activate = mock(Animation.class);
		when(activate.getId()).thenReturn(12169);
		DynamicObject renderable = mock(DynamicObject.class);
		when(renderable.getAnimation()).thenReturn(activate);
		GameObject glyph = mock(GameObject.class);
		when(glyph.getId()).thenReturn(500);
		when(glyph.getRenderable()).thenReturn(renderable);
		GameObjectSpawned spawned = new GameObjectSpawned();
		spawned.setGameObject(glyph);

		translator.gameObjectSpawned(spawned);

		assertEquals(List.of(new ObjectAnimationObserved(12, 500, new Position(1, 2, 0), 12169)), translator.pollObjectAnimations());
		assertTrue(translator.pollObjectAnimations().isEmpty());
	}

	@Test
	public void inventoryChangesBecomeDeltasAgainstTheBaseline()
	{
		translator.reset(Map.of(385, 5));
		ItemContainer inventory = mock(ItemContainer.class);
		when(inventory.getItems()).thenReturn(new Item[]{
			new Item(385, 1), new Item(385, 1), new Item(385, 1), new Item(-1, 0), new Item(3024, 1)});
		ItemContainerChanged event = new ItemContainerChanged(InventoryID.INV, inventory);

		List<DomainEvent> deltas = translator.itemContainerChanged(event);

		assertEquals(List.of(new InventoryDelta(12, 385, "Item 385", -2), new InventoryDelta(12, 3024, "Item 3024", 1)), deltas);
		assertTrue(translator.itemContainerChanged(event).isEmpty());
	}

	@Test
	public void gameStatesMapToTheDomain()
	{
		assertEquals(List.of(new GameStateObserved(12, GameStateKind.LOADING)), translator.gameState(GameState.LOADING));
		assertEquals(List.of(new GameStateObserved(12, GameStateKind.OTHER)), translator.gameState(GameState.STARTING));
	}

	@Test
	public void entryChoiceIgnoresColourTagsAndCase()
	{
		assertEquals(Optional.of(EntryChoice.TRAVEL), EventTranslator.entryChoice("Travel"));
		assertEquals(Optional.of(EntryChoice.JOIN), EventTranslator.entryChoice("<col=ff9040>join</col>"));
		assertEquals(Optional.empty(), EventTranslator.entryChoice("Talk-to"));
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'com.yamareviewer.adapter.recording.*'`
Expected: FAIL — `cannot find symbol` for `PositionReader`, `ActorResolver`, `EventTranslator`.

- [ ] **Step 3: Write `PositionReader` and `ActorResolver`**

`src/main/java/com/yamareviewer/adapter/recording/PositionReader.java`:

```java
package com.yamareviewer.adapter.recording;

import com.yamareviewer.domain.event.Position;
import javax.annotation.Nullable;
import net.runelite.api.Client;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

/** The one place that turns local points into template coordinates, so positions are never mixed. */
public class PositionReader
{
	private final Client client;

	public PositionReader(Client client)
	{
		this.client = client;
	}

	@Nullable
	public Position position(@Nullable LocalPoint local)
	{
		WorldPoint world = toWorld(local);
		return world == null ? null : new Position(world.getX(), world.getY(), world.getPlane());
	}

	public int regionId(@Nullable LocalPoint local)
	{
		WorldPoint world = toWorld(local);
		return world == null ? -1 : world.getRegionID();
	}

	@Nullable
	private WorldPoint toWorld(@Nullable LocalPoint local)
	{
		return local == null ? null : WorldPoint.fromLocalInstance(client, local);
	}
}
```

`src/main/java/com/yamareviewer/adapter/recording/ActorResolver.java`:

```java
package com.yamareviewer.adapter.recording;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.RoleKind;
import java.util.Optional;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.client.util.Text;

/** Maps RuneLite actors to domain actors. Not final so tests can mock it. */
public class ActorResolver
{
	private final Client client;
	private final IdRegistry ids;
	private final Supplier<Optional<String>> partnerName;

	public ActorResolver(Client client, IdRegistry ids, Supplier<Optional<String>> partnerName)
	{
		this.client = client;
		this.ids = ids;
		this.partnerName = partnerName;
	}

	public Actor resolve(@Nullable net.runelite.api.Actor actor)
	{
		if (actor == null)
		{
			return Actor.other("none");
		}
		if (actor == client.getLocalPlayer())
		{
			return Actor.SELF;
		}
		if (actor instanceof Player)
		{
			String name = actor.getName() == null ? "" : Text.sanitize(actor.getName());
			return partnerName.get().filter(name::equals).isPresent() ? Actor.PARTNER : Actor.other(name);
		}
		if (actor instanceof NPC)
		{
			NPC npc = (NPC) actor;
			int npcId = npc.getId();
			if (ids.is(Role.YAMA, npcId))
			{
				return Actor.YAMA;
			}
			if (ids.is(Role.JUDGE, npcId))
			{
				return Actor.JUDGE;
			}
			if (ids.is(Role.VOID_FLARE, npcId))
			{
				return Actor.flare(npc.getIndex());
			}
			if (ids.roleOf(RoleKind.NPC, npcId).isPresent())
			{
				return Actor.npc(npcId);
			}
			return Actor.other("npc " + npcId);
		}
		return Actor.other("unknown");
	}
}
```

- [ ] **Step 4: Write `EventTranslator`**

`src/main/java/com/yamareviewer/adapter/recording/EventTranslator.java`:

```java
package com.yamareviewer.adapter.recording;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.ActorKind;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.event.EntryChoice;
import com.yamareviewer.domain.event.GameMessageObserved;
import com.yamareviewer.domain.event.GameStateKind;
import com.yamareviewer.domain.event.GameStateObserved;
import com.yamareviewer.domain.event.GraphicObserved;
import com.yamareviewer.domain.event.GroundGraphicObserved;
import com.yamareviewer.domain.event.HitsplatKind;
import com.yamareviewer.domain.event.HitsplatObserved;
import com.yamareviewer.domain.event.InventoryDelta;
import com.yamareviewer.domain.event.NpcChangedObserved;
import com.yamareviewer.domain.event.NpcDespawnObserved;
import com.yamareviewer.domain.event.NpcSpawnObserved;
import com.yamareviewer.domain.event.ObjectAnimationObserved;
import com.yamareviewer.domain.event.ObjectDespawnObserved;
import com.yamareviewer.domain.event.ObjectSpawnObserved;
import com.yamareviewer.domain.event.OverheadTextObserved;
import com.yamareviewer.domain.event.ProjectileObserved;
import com.yamareviewer.domain.event.VarbitObserved;
import com.yamareviewer.domain.event.WidgetTextObserved;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.RoleKind;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import net.runelite.api.ActorSpotAnim;
import net.runelite.api.Animation;
import net.runelite.api.DynamicObject;
import net.runelite.api.GameObject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameState;
import net.runelite.api.GraphicsObject;
import net.runelite.api.Hitsplat;
import net.runelite.api.HitsplatID;
import net.runelite.api.Item;
import net.runelite.api.NPC;
import net.runelite.api.Projectile;
import net.runelite.api.Renderable;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.GraphicsObjectCreated;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.NpcChanged;
import net.runelite.api.events.OverheadTextChanged;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.util.Text;

/**
 * The anti-corruption layer: turns RuneLite events into raw domain observations. It never classifies.
 * Events about unrelated actors are dropped unless capture mode is on; ground graphics, projectiles and
 * objects are kept unfiltered because everything of that kind in the arena belongs to the fight.
 * Client thread only.
 */
public final class EventTranslator
{
	private static final int CLIENT_CYCLES_PER_TICK = 30;

	private final IdRegistry ids;
	private final ActorResolver actors;
	private final IntSupplier tick;
	private final BooleanSupplier capture;
	private final IntFunction<String> itemNames;
	private final PositionReader positions;

	private final Set<String> seenSpotAnims = new HashSet<>();
	private final Set<Projectile> seenProjectiles = Collections.newSetFromMap(new IdentityHashMap<>());
	private final Map<GameObject, Integer> trackedObjects = new IdentityHashMap<>();
	private Map<Integer, Integer> inventory = Map.of();

	public EventTranslator(IdRegistry ids, ActorResolver actors, IntSupplier tick, BooleanSupplier capture,
		IntFunction<String> itemNames, PositionReader positions)
	{
		this.ids = ids;
		this.actors = actors;
		this.tick = tick;
		this.capture = capture;
		this.itemNames = itemNames;
		this.positions = positions;
	}

	/** Called when a fight starts: forget the previous fight and set the inventory baseline. */
	public void reset(Map<Integer, Integer> inventoryNow)
	{
		seenSpotAnims.clear();
		seenProjectiles.clear();
		inventory = Map.copyOf(inventoryNow);
	}

	public List<DomainEvent> animationChanged(AnimationChanged event)
	{
		int animationId = event.getActor().getAnimation();
		if (animationId == -1)
		{
			return List.of();
		}
		Actor actor = actors.resolve(event.getActor());
		return keep(actor) ? List.of(new AnimationObserved(tick.getAsInt(), actor, animationId)) : List.of();
	}

	public List<DomainEvent> graphicChanged(GraphicChanged event)
	{
		Actor actor = actors.resolve(event.getActor());
		if (!keep(actor))
		{
			return List.of();
		}
		List<DomainEvent> result = new ArrayList<>();
		for (ActorSpotAnim spotAnim : event.getActor().getSpotAnims())
		{
			String key = actor + "/" + spotAnim.getId() + "/" + spotAnim.getStartCycle();
			if (seenSpotAnims.add(key))
			{
				result.add(new GraphicObserved(tick.getAsInt(), actor, spotAnim.getId()));
			}
		}
		return result;
	}

	public List<DomainEvent> graphicsObjectCreated(GraphicsObjectCreated event)
	{
		GraphicsObject graphic = event.getGraphicsObject();
		return List.of(new GroundGraphicObserved(tick.getAsInt(), graphic.getId(), positions.position(graphic.getLocation())));
	}

	public List<DomainEvent> projectileMoved(ProjectileMoved event)
	{
		Projectile projectile = event.getProjectile();
		if (!seenProjectiles.add(projectile))
		{
			return List.of();
		}
		net.runelite.api.Actor target = projectile.getTargetActor();
		int ticksToLand = (projectile.getRemainingCycles() + CLIENT_CYCLES_PER_TICK - 1) / CLIENT_CYCLES_PER_TICK;
		return List.of(new ProjectileObserved(tick.getAsInt(), projectile.getId(),
			target == null ? null : actors.resolve(target), tick.getAsInt() + ticksToLand));
	}

	public List<DomainEvent> hitsplatApplied(HitsplatApplied event)
	{
		Actor target = actors.resolve(event.getActor());
		if (!keep(target))
		{
			return List.of();
		}
		Hitsplat hitsplat = event.getHitsplat();
		return List.of(new HitsplatObserved(tick.getAsInt(), target, kindOf(hitsplat), hitsplat.getAmount(),
			hitsplat.getHitsplatType(), hitsplat.isMine()));
	}

	public List<DomainEvent> overheadTextChanged(OverheadTextChanged event)
	{
		Actor actor = actors.resolve(event.getActor());
		return keep(actor) ? List.of(new OverheadTextObserved(tick.getAsInt(), actor, event.getOverheadText())) : List.of();
	}

	/** Only system messages; player chat is never recorded. */
	public List<DomainEvent> chatMessage(ChatMessage event)
	{
		return event.getType() == ChatMessageType.GAMEMESSAGE
			? List.of(new GameMessageObserved(tick.getAsInt(), event.getMessage()))
			: List.of();
	}

	/** VarbitChanged fires for every varbit and varp, so only varbits with a role are kept. */
	public List<DomainEvent> varbitChanged(VarbitChanged event)
	{
		return ids.roleOf(RoleKind.VARBIT, event.getVarbitId()).isPresent()
			? List.of(new VarbitObserved(tick.getAsInt(), event.getVarbitId(), event.getValue()))
			: List.of();
	}

	public List<DomainEvent> npcSpawned(NPC npc)
	{
		Actor actor = actors.resolve(npc);
		return keep(actor) ? List.of(new NpcSpawnObserved(tick.getAsInt(), actor, npc.getId(), npc.getIndex())) : List.of();
	}

	public List<DomainEvent> npcDespawned(NPC npc, boolean dying)
	{
		Actor actor = actors.resolve(npc);
		return keep(actor) ? List.of(new NpcDespawnObserved(tick.getAsInt(), actor, npc.getId(), npc.getIndex(), dying)) : List.of();
	}

	public List<DomainEvent> npcChanged(NpcChanged event)
	{
		NPC npc = event.getNpc();
		Actor actor = actors.resolve(npc);
		return keep(actor) ? List.of(new NpcChangedObserved(tick.getAsInt(), actor, event.getOld().getId(), npc.getId())) : List.of();
	}

	/** Also starts tracking glyph objects (every object in capture mode) for animation polling. */
	public List<DomainEvent> gameObjectSpawned(GameObjectSpawned event)
	{
		GameObject object = event.getGameObject();
		if (capture.getAsBoolean() || ids.roleOf(RoleKind.OBJECT, object.getId()).isPresent())
		{
			trackedObjects.put(object, -1);
		}
		return List.of(new ObjectSpawnObserved(tick.getAsInt(), object.getId(), positions.position(object.getLocalLocation())));
	}

	public List<DomainEvent> gameObjectDespawned(GameObjectDespawned event)
	{
		GameObject object = event.getGameObject();
		trackedObjects.remove(object);
		return List.of(new ObjectDespawnObserved(tick.getAsInt(), object.getId(), positions.position(object.getLocalLocation())));
	}

	/** RuneLite has no event for object animations, so tracked objects are polled once per tick. */
	public List<DomainEvent> pollObjectAnimations()
	{
		List<DomainEvent> result = new ArrayList<>();
		for (Map.Entry<GameObject, Integer> entry : trackedObjects.entrySet())
		{
			GameObject object = entry.getKey();
			int animationId = animationOf(object);
			if (animationId != entry.getValue())
			{
				entry.setValue(animationId);
				if (animationId != -1)
				{
					result.add(new ObjectAnimationObserved(tick.getAsInt(), object.getId(),
						positions.position(object.getLocalLocation()), animationId));
				}
			}
		}
		return result;
	}

	public List<DomainEvent> itemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() != InventoryID.INV)
		{
			return List.of();
		}
		Map<Integer, Integer> now = aggregate(event.getItemContainer().getItems());
		Set<Integer> itemIds = new TreeSet<>(inventory.keySet());
		itemIds.addAll(now.keySet());
		List<DomainEvent> result = new ArrayList<>();
		for (int itemId : itemIds)
		{
			int change = now.getOrDefault(itemId, 0) - inventory.getOrDefault(itemId, 0);
			if (change != 0)
			{
				result.add(new InventoryDelta(tick.getAsInt(), itemId, itemNames.apply(itemId), change));
			}
		}
		inventory = now;
		return result;
	}

	public List<DomainEvent> widgetText(int componentId, String text)
	{
		return List.of(new WidgetTextObserved(tick.getAsInt(), componentId, text));
	}

	public List<DomainEvent> gameState(GameState state)
	{
		return List.of(new GameStateObserved(tick.getAsInt(), kindOf(state)));
	}

	/** A contract item consumed at the challenge, just before the fight started (spec 6.3). */
	public DomainEvent contractConsumed(int itemId)
	{
		return new InventoryDelta(tick.getAsInt(), itemId, itemNames.apply(itemId), -1);
	}

	/** Quantities per item id, ignoring empty slots. */
	public static Map<Integer, Integer> aggregate(Item[] items)
	{
		Map<Integer, Integer> quantities = new TreeMap<>();
		for (Item item : items)
		{
			if (item.getId() >= 0 && item.getQuantity() > 0)
			{
				quantities.merge(item.getId(), item.getQuantity(), Integer::sum);
			}
		}
		return quantities;
	}

	public static Optional<EntryChoice> entryChoice(String menuOption)
	{
		String option = Text.removeTags(menuOption).trim();
		if (option.equalsIgnoreCase("Travel"))
		{
			return Optional.of(EntryChoice.TRAVEL);
		}
		if (option.equalsIgnoreCase("Join"))
		{
			return Optional.of(EntryChoice.JOIN);
		}
		return Optional.empty();
	}

	static HitsplatKind kindOf(Hitsplat hitsplat)
	{
		int type = hitsplat.getHitsplatType();
		if (type == HitsplatID.HEAL)
		{
			return HitsplatKind.HEAL;
		}
		if (type == HitsplatID.BLOCK_ME || type == HitsplatID.BLOCK_OTHER)
		{
			return HitsplatKind.BLOCK;
		}
		return hitsplat.getAmount() > 0 ? HitsplatKind.DAMAGE : HitsplatKind.OTHER;
	}

	static GameStateKind kindOf(GameState state)
	{
		switch (state)
		{
			case LOADING:
				return GameStateKind.LOADING;
			case LOGGED_IN:
				return GameStateKind.LOGGED_IN;
			case HOPPING:
				return GameStateKind.HOPPING;
			case LOGIN_SCREEN:
				return GameStateKind.LOGIN_SCREEN;
			case CONNECTION_LOST:
				return GameStateKind.CONNECTION_LOST;
			default:
				return GameStateKind.OTHER;
		}
	}

	private static int animationOf(GameObject object)
	{
		Renderable renderable = object.getRenderable();
		if (renderable instanceof DynamicObject)
		{
			Animation animation = ((DynamicObject) renderable).getAnimation();
			return animation == null ? -1 : animation.getId();
		}
		return -1;
	}

	private boolean keep(Actor actor)
	{
		return capture.getAsBoolean() || actor.getKind() != ActorKind.OTHER;
	}
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.yamareviewer.adapter.recording.*'`
Expected: PASS (15 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/yamareviewer/adapter/recording src/test/java/com/yamareviewer/adapter/recording
git commit -m "feat: translate RuneLite events into raw domain observations"
```

---

### Task 8: Tick sampling and supply snapshots

**Files:**
- Create: `src/main/java/com/yamareviewer/adapter/recording/ItemLookup.java`, `ItemManagerLookup.java`, `TickSampler.java`, `SnapshotReader.java`
- Test: `src/test/java/com/yamareviewer/adapter/recording/SnapshotReaderTest.java`, `TickSamplerTest.java`

**Interfaces:**
- Consumes: Tasks 2, 4 and 7.
- Produces: `ItemLookup { String name(int); int gePrice(int); int haPrice(int); }`; `ItemManagerLookup(ItemManager)`; `TickSampler(Client, ActorResolver, PositionReader)` with `TickState sample(int tick, NPC yama, Player partner)` (non-final for mocking); `SnapshotReader(Client, ItemLookup)` implementing `SnapshotSource`.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/yamareviewer/adapter/recording/SnapshotReaderTest.java`:

```java
package com.yamareviewer.adapter.recording;

import com.yamareviewer.domain.event.SnapshotKind;
import com.yamareviewer.domain.event.SuppliesSnapshot;
import com.yamareviewer.domain.event.SupplyItem;
import java.util.List;
import java.util.Optional;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;
import static org.junit.Assert.assertEquals;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SnapshotReaderTest
{
	private final Client client = mock(Client.class);
	private final ItemLookup items = new ItemLookup()
	{
		@Override
		public String name(int itemId)
		{
			return "Item " + itemId;
		}

		@Override
		public int gePrice(int itemId)
		{
			return itemId * 10;
		}

		@Override
		public int haPrice(int itemId)
		{
			return itemId;
		}
	};
	private final SnapshotReader reader = new SnapshotReader(client, items);

	@Before
	public void setUp()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		ItemContainer inventory = mock(ItemContainer.class);
		when(inventory.getItems()).thenReturn(new Item[]{new Item(385, 1), new Item(385, 1), new Item(-1, 0), new Item(556, 10)});
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inventory);
		EnumComposition runes = mock(EnumComposition.class);
		when(runes.getIntValue(1)).thenReturn(556);
		when(client.getEnum(EnumID.RUNEPOUCH_RUNE)).thenReturn(runes);
		when(client.getVarbitValue(VarbitID.RUNE_POUCH_TYPE_6)).thenReturn(1);
		when(client.getVarbitValue(VarbitID.RUNE_POUCH_QUANTITY_6)).thenReturn(1000);
	}

	@Test
	public void aggregatesInventoryAndAllSixRunePouchSlotsWithPrices()
	{
		Optional<SuppliesSnapshot> snapshot = reader.take(4, SnapshotKind.START);

		assertEquals(Optional.of(new SuppliesSnapshot(4, SnapshotKind.START, List.of(
			new SupplyItem(385, "Item 385", 2, 3850, 385),
			new SupplyItem(556, "Item 556", 1010, 5560, 556)))), snapshot);
	}

	@Test
	public void notLoggedInGivesNoSnapshot()
	{
		when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);

		assertEquals(Optional.empty(), reader.take(4, SnapshotKind.END));
	}
}
```

`src/test/java/com/yamareviewer/adapter/recording/TickSamplerTest.java`:

```java
package com.yamareviewer.adapter.recording;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.ProtectionPrayer;
import com.yamareviewer.domain.event.TickState;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import static org.junit.Assert.assertEquals;
import org.junit.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TickSamplerTest
{
	@Test
	public void readsPrayersFromVarbitsAndEnergiesAsPercent()
	{
		Client client = mock(Client.class);
		Player self = mock(Player.class);
		NPC yama = mock(NPC.class);
		ActorResolver actors = mock(ActorResolver.class);
		when(client.getLocalPlayer()).thenReturn(self);
		when(yama.getInteracting()).thenReturn(self);
		when(actors.resolve(self)).thenReturn(Actor.SELF);
		when(client.getVarbitValue(VarbitID.PRAYER_PROTECTFROMMISSILES)).thenReturn(1);
		when(client.getBoostedSkillLevel(Skill.HITPOINTS)).thenReturn(87);
		when(client.getBoostedSkillLevel(Skill.PRAYER)).thenReturn(60);
		when(client.getVarpValue(VarPlayerID.SA_ENERGY)).thenReturn(750);
		when(client.getEnergy()).thenReturn(6400);

		TickState state = new TickSampler(client, actors, mock(PositionReader.class)).sample(9, yama, null);

		assertEquals(Set.of(ProtectionPrayer.MISSILES), state.getPrayers());
		assertEquals(87, state.getHitpoints());
		assertEquals(60, state.getPrayerPoints());
		assertEquals(75, state.getSpecEnergy());
		assertEquals(64, state.getRunEnergy());
		assertEquals(-1, state.getWeaponId());
		assertEquals(Actor.SELF, state.getYamaTarget());
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'com.yamareviewer.adapter.recording.SnapshotReaderTest' --tests 'com.yamareviewer.adapter.recording.TickSamplerTest'`
Expected: FAIL — `cannot find symbol` for `ItemLookup`, `SnapshotReader`, `TickSampler`.

- [ ] **Step 3: Write the item lookup**

`src/main/java/com/yamareviewer/adapter/recording/ItemLookup.java`:

```java
package com.yamareviewer.adapter.recording;

/** Item facts; implementations may only be called on the client thread. */
public interface ItemLookup
{
	String name(int itemId);

	int gePrice(int itemId);

	int haPrice(int itemId);
}
```

`src/main/java/com/yamareviewer/adapter/recording/ItemManagerLookup.java`:

```java
package com.yamareviewer.adapter.recording;

import net.runelite.client.game.ItemManager;

/** ItemManager reads item definitions, which requires the client thread. */
public final class ItemManagerLookup implements ItemLookup
{
	private final ItemManager itemManager;

	public ItemManagerLookup(ItemManager itemManager)
	{
		this.itemManager = itemManager;
	}

	@Override
	public String name(int itemId)
	{
		return itemManager.getItemComposition(itemId).getName();
	}

	@Override
	public int gePrice(int itemId)
	{
		return itemManager.getItemPrice(itemId);
	}

	@Override
	public int haPrice(int itemId)
	{
		return itemManager.getItemComposition(itemId).getHaPrice();
	}
}
```

- [ ] **Step 4: Write `SnapshotReader` and `TickSampler`**

`src/main/java/com/yamareviewer/adapter/recording/SnapshotReader.java`:

```java
package com.yamareviewer.adapter.recording;

import com.yamareviewer.application.port.SnapshotSource;
import com.yamareviewer.domain.event.SnapshotKind;
import com.yamareviewer.domain.event.SuppliesSnapshot;
import com.yamareviewer.domain.event.SupplyItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.GameState;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;

/** Inventory plus the six rune pouch slots, named and priced on the client thread. */
public final class SnapshotReader implements SnapshotSource
{
	private static final int[] RUNE_TYPES = {
		VarbitID.RUNE_POUCH_TYPE_1, VarbitID.RUNE_POUCH_TYPE_2, VarbitID.RUNE_POUCH_TYPE_3,
		VarbitID.RUNE_POUCH_TYPE_4, VarbitID.RUNE_POUCH_TYPE_5, VarbitID.RUNE_POUCH_TYPE_6
	};
	private static final int[] RUNE_QUANTITIES = {
		VarbitID.RUNE_POUCH_QUANTITY_1, VarbitID.RUNE_POUCH_QUANTITY_2, VarbitID.RUNE_POUCH_QUANTITY_3,
		VarbitID.RUNE_POUCH_QUANTITY_4, VarbitID.RUNE_POUCH_QUANTITY_5, VarbitID.RUNE_POUCH_QUANTITY_6
	};

	private final Client client;
	private final ItemLookup lookup;

	public SnapshotReader(Client client, ItemLookup lookup)
	{
		this.client = client;
		this.lookup = lookup;
	}

	@Override
	public Optional<SuppliesSnapshot> take(int tick, SnapshotKind kind)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return Optional.empty();
		}
		ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		if (inventory == null)
		{
			return Optional.empty();
		}
		Map<Integer, Integer> quantities = new TreeMap<>(EventTranslator.aggregate(inventory.getItems()));
		EnumComposition runeItems = client.getEnum(EnumID.RUNEPOUCH_RUNE);
		for (int i = 0; i < RUNE_TYPES.length; i++)
		{
			int type = client.getVarbitValue(RUNE_TYPES[i]);
			int quantity = client.getVarbitValue(RUNE_QUANTITIES[i]);
			if (type > 0 && quantity > 0)
			{
				quantities.merge(runeItems.getIntValue(type), quantity, Integer::sum);
			}
		}
		List<SupplyItem> items = new ArrayList<>();
		quantities.forEach((itemId, quantity) -> items.add(
			new SupplyItem(itemId, lookup.name(itemId), quantity, lookup.gePrice(itemId), lookup.haPrice(itemId))));
		return Optional.of(new SuppliesSnapshot(tick, kind, List.copyOf(items)));
	}
}
```

`src/main/java/com/yamareviewer/adapter/recording/TickSampler.java`:

```java
package com.yamareviewer.adapter.recording;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.ProtectionPrayer;
import com.yamareviewer.domain.event.TickState;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import javax.annotation.Nullable;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

/** Reads the end-of-tick state. Client thread only. Not final so tests can mock it. */
public class TickSampler
{
	private final Client client;
	private final ActorResolver actors;
	private final PositionReader positions;

	public TickSampler(Client client, ActorResolver actors, PositionReader positions)
	{
		this.client = client;
		this.actors = actors;
		this.positions = positions;
	}

	public TickState sample(int tick, @Nullable NPC yama, @Nullable Player partner)
	{
		Set<ProtectionPrayer> prayers = EnumSet.noneOf(ProtectionPrayer.class);
		if (client.getVarbitValue(VarbitID.PRAYER_PROTECTFROMMAGIC) == 1)
		{
			prayers.add(ProtectionPrayer.MAGIC);
		}
		if (client.getVarbitValue(VarbitID.PRAYER_PROTECTFROMMISSILES) == 1)
		{
			prayers.add(ProtectionPrayer.MISSILES);
		}
		if (client.getVarbitValue(VarbitID.PRAYER_PROTECTFROMMELEE) == 1)
		{
			prayers.add(ProtectionPrayer.MELEE);
		}

		Actor yamaTarget = yama == null || yama.getInteracting() == null ? null : actors.resolve(yama.getInteracting());
		Player self = client.getLocalPlayer();

		return new TickState(
			tick,
			Collections.unmodifiableSet(prayers),
			client.getBoostedSkillLevel(Skill.HITPOINTS),
			client.getBoostedSkillLevel(Skill.PRAYER),
			client.getVarpValue(VarPlayerID.SA_ENERGY) / 10,
			client.getEnergy() / 100,
			weaponId(),
			yamaTarget,
			self == null ? null : positions.position(self.getLocalLocation()),
			partner == null ? null : positions.position(partner.getLocalLocation()));
	}

	private int weaponId()
	{
		ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		if (worn == null)
		{
			return -1;
		}
		Item weapon = worn.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
		return weapon == null ? -1 : weapon.getId();
	}
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.yamareviewer.adapter.recording.SnapshotReaderTest' --tests 'com.yamareviewer.adapter.recording.TickSamplerTest'`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/yamareviewer/adapter/recording src/test/java/com/yamareviewer/adapter/recording
git commit -m "feat: sample tick state and priced supply snapshots"
```

---

### Task 9: GameEventListener

**Files:**
- Create: `src/main/java/com/yamareviewer/adapter/recording/GameEventListener.java`
- Test: `src/test/java/com/yamareviewer/adapter/recording/GameEventListenerTest.java`

**Interfaces:**
- Consumes: Tasks 3, 4, 6, 7 and 8.
- Produces: `GameEventListener(Client, IdRegistry, KillSession, EventTranslator, TickSampler, PositionReader, NpcUtil, Supplier<Future<?>> pendingWrite)` with public `@Subscribe` methods; the plugin registers it on the `EventBus`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/yamareviewer/adapter/recording/GameEventListenerTest.java`:

```java
package com.yamareviewer.adapter.recording;

import com.yamareviewer.application.command.KillSession;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.EntryChoice;
import com.yamareviewer.domain.event.EntryChosen;
import com.yamareviewer.domain.event.InventoryDelta;
import com.yamareviewer.domain.event.TickState;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.PlayerSpawned;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.game.NpcUtil;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class GameEventListenerTest
{
	private final Client client = mock(Client.class);
	private final Player self = mock(Player.class);
	private final NPC yama = mock(NPC.class);
	private final PositionReader positions = mock(PositionReader.class);
	private final TickSampler sampler = mock(TickSampler.class);
	private final NpcUtil npcUtil = mock(NpcUtil.class);
	private final List<KillLog> ended = new ArrayList<>();
	private final IdRegistry ids = new IdRegistry(
		Map.of(Role.YAMA, Set.of(100), Role.YAMAS_DOMAIN, Set.of(6045), Role.VOICE_OF_YAMA, Set.of(200),
			Role.CONTRACT_ITEM_BLOODIED_BLOWS, Set.of(700)),
		Map.of());
	private KillSession session;
	private GameEventListener listener;

	@Before
	public void setUp()
	{
		when(client.getLocalPlayer()).thenReturn(self);
		when(self.getName()).thenReturn("Me");
		when(positions.regionId(any())).thenReturn(6045);
		when(yama.getId()).thenReturn(100);
		when(sampler.sample(anyInt(), any(), any())).thenAnswer(invocation ->
			new TickState(invocation.getArgument(0), Set.of(), 99, 99, 100, 100, -1, null, null, null));
		session = new KillSession(ended::add, (tick, kind) -> Optional.empty(), Clock.systemUTC(),
			() -> "kill", "0.1.0", "f00d", () -> false);
		ActorResolver actors = new ActorResolver(client, ids, session::partnerName);
		EventTranslator translator = new EventTranslator(ids, actors, session::currentTick, () -> false, itemId -> "item", positions);
		listener = new GameEventListener(client, ids, session, translator, sampler, positions, npcUtil,
			() -> CompletableFuture.completedFuture(null));
	}

	@Test
	public void yamaSpawningInTheDomainStartsAFight()
	{
		listener.onNpcSpawned(new NpcSpawned(yama));

		assertTrue(session.isFighting());
	}

	@Test
	public void yamaOutsideTheDomainIsIgnored()
	{
		when(positions.regionId(any())).thenReturn(1234);

		listener.onNpcSpawned(new NpcSpawned(yama));

		assertFalse(session.isFighting());
	}

	@Test
	public void deathEndsOnTheNextTickKeepingThatTicksState()
	{
		listener.onNpcSpawned(new NpcSpawned(yama));
		listener.onGameTick(new GameTick());
		listener.onActorDeath(new ActorDeath(self));

		assertTrue(ended.isEmpty());
		listener.onGameTick(new GameTick());

		KillLog kill = ended.get(0);
		List<DomainEvent> events = kill.getEvents();
		assertEquals(EndReason.PLAYER_DIED, kill.endReason());
		assertTrue(events.get(events.size() - 2) instanceof TickState);
		assertEquals(1, events.get(events.size() - 2).getTick());
		assertEquals(1, kill.lastTick());
	}

	@Test
	public void yamaDespawningWhileDyingIsAKill()
	{
		listener.onNpcSpawned(new NpcSpawned(yama));
		when(npcUtil.isDying(yama)).thenReturn(true);
		listener.onNpcDespawned(new NpcDespawned(yama));
		listener.onGameTick(new GameTick());

		assertEquals(EndReason.YAMA_DIED, ended.get(0).endReason());
	}

	@Test
	public void yamaDespawningOnASceneReloadIsNotAKill()
	{
		listener.onNpcSpawned(new NpcSpawned(yama));
		listener.onNpcDespawned(new NpcDespawned(yama));
		listener.onGameTick(new GameTick());

		assertTrue(ended.isEmpty());
		assertTrue(session.isFighting());
	}

	@Test
	public void loggingOutEndsTheFightAsLeft()
	{
		listener.onNpcSpawned(new NpcSpawned(yama));
		GameStateChanged loggedOut = new GameStateChanged();
		loggedOut.setGameState(GameState.LOGIN_SCREEN);

		listener.onGameStateChanged(loggedOut);

		assertEquals(EndReason.LEFT, ended.get(0).endReason());
	}

	@Test
	public void travelAtTheVoiceIsRecordedWhenTheFightStarts()
	{
		NPC voice = mock(NPC.class);
		when(voice.getId()).thenReturn(200);
		MenuEntry entry = mock(MenuEntry.class);
		when(entry.getNpc()).thenReturn(voice);
		when(entry.getOption()).thenReturn("Travel");
		when(positions.regionId(any())).thenReturn(1234);
		listener.onMenuOptionClicked(new MenuOptionClicked(entry));

		when(positions.regionId(any())).thenReturn(6045);
		listener.onNpcSpawned(new NpcSpawned(yama));
		session.leave();

		assertEquals(EntryChoice.TRAVEL, ended.get(0).eventsOf(EntryChosen.class).get(0).getChoice());
	}

	@Test
	public void aPartnerLoadingInLateIsSeen()
	{
		listener.onNpcSpawned(new NpcSpawned(yama));
		Player buddy = mock(Player.class);
		when(buddy.getName()).thenReturn("Buddy");

		listener.onPlayerSpawned(new PlayerSpawned(buddy));

		assertEquals(Optional.of("Buddy"), session.partnerName());
	}

	@Test
	public void aContractConsumedAtTheChallengeIsRecordedAtFightStart()
	{
		listener.onGameTick(new GameTick());
		ItemContainer withContract = mock(ItemContainer.class);
		when(withContract.getItems()).thenReturn(new Item[]{new Item(700, 1)});
		listener.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, withContract));

		listener.onNpcSpawned(new NpcSpawned(yama));
		session.leave();

		assertEquals(List.of(new InventoryDelta(0, 700, "item", -1)), ended.get(0).eventsOf(InventoryDelta.class));
	}

	@Test
	public void theLocalPlayerIsNeverAPartner()
	{
		listener.onPlayerSpawned(new PlayerSpawned(self));
		listener.onNpcSpawned(new NpcSpawned(yama));

		assertEquals(Optional.empty(), session.partnerName());
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'com.yamareviewer.adapter.recording.GameEventListenerTest'`
Expected: FAIL — `cannot find symbol: class GameEventListener`.

- [ ] **Step 3: Write `GameEventListener`**

`src/main/java/com/yamareviewer/adapter/recording/GameEventListener.java`:

```java
package com.yamareviewer.adapter.recording;

import com.yamareviewer.application.command.FightStart;
import com.yamareviewer.application.command.KillSession;
import com.yamareviewer.application.command.SessionState;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.GraphicsObjectCreated;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.NpcChanged;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.OverheadTextChanged;
import net.runelite.api.events.PlayerDespawned;
import net.runelite.api.events.PlayerSpawned;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.game.NpcUtil;
import net.runelite.client.util.Text;

/**
 * The only receiver of RuneLite game events. Decides when a fight starts and ends and forwards
 * observations; it never classifies and never touches output. All handlers run on the client thread.
 */
public class GameEventListener
{
	/** How long before the fight a contract item may have left the inventory (spec 6.3). */
	private static final int CONTRACT_LOOKBACK_TICKS = 20;

	private final Client client;
	private final IdRegistry ids;
	private final KillSession session;
	private final EventTranslator translator;
	private final TickSampler sampler;
	private final PositionReader positions;
	private final NpcUtil npcUtil;
	private final Supplier<Future<?>> pendingWrite;

	private final Map<String, Player> players = new HashMap<>();
	private final Set<Integer> pendingWidgetReads = new LinkedHashSet<>();
	private final Map<Integer, Integer> contractItemsLastSeen = new HashMap<>();
	private NPC yama;

	public GameEventListener(Client client, IdRegistry ids, KillSession session, EventTranslator translator,
		TickSampler sampler, PositionReader positions, NpcUtil npcUtil, Supplier<Future<?>> pendingWrite)
	{
		this.client = client;
		this.ids = ids;
		this.session = session;
		this.translator = translator;
		this.sampler = sampler;
		this.positions = positions;
		this.npcUtil = npcUtil;
		this.pendingWrite = pendingWrite;
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		session.updateRegion(inYamasDomain());
		if (!session.isFighting())
		{
			return;
		}
		record(translator.pollObjectAnimations());
		readPendingWidgets();
		session.record(sampler.sample(session.currentTick(), yama, partner()));
		session.endTick();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		record(translator.gameState(state));
		if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
		{
			session.leave();
			players.clear();
			pendingWidgetReads.clear();
			contractItemsLastSeen.clear();
			yama = null;
		}
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		NPC npc = event.getNpc();
		if (ids.is(Role.YAMA, npc.getId()))
		{
			yama = npc;
			startFightIfArmed();
		}
		record(translator.npcSpawned(npc));
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		NPC npc = event.getNpc();
		boolean dying = npcUtil.isDying(npc);
		record(translator.npcDespawned(npc, dying));
		if (npc == yama)
		{
			if (dying)
			{
				session.yamaDied();
			}
			yama = null;
		}
	}

	@Subscribe
	public void onNpcChanged(NpcChanged event)
	{
		record(translator.npcChanged(event));
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (event.getActor() == client.getLocalPlayer())
		{
			session.playerDied();
		}
		else if (yama != null && event.getActor() == yama)
		{
			session.yamaDied();
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		NPC npc = event.getMenuEntry().getNpc();
		if (npc != null && ids.is(Role.VOICE_OF_YAMA, npc.getId()))
		{
			EventTranslator.entryChoice(event.getMenuOption()).ifPresent(session::chooseEntry);
		}
	}

	@Subscribe
	public void onPlayerSpawned(PlayerSpawned event)
	{
		Player player = event.getPlayer();
		if (player == client.getLocalPlayer() || player.getName() == null)
		{
			return;
		}
		String name = Text.sanitize(player.getName());
		players.put(name, player);
		session.playerSeen(name);
	}

	@Subscribe
	public void onPlayerDespawned(PlayerDespawned event)
	{
		Player player = event.getPlayer();
		if (player == client.getLocalPlayer() || player.getName() == null)
		{
			return;
		}
		String name = Text.sanitize(player.getName());
		players.remove(name, player);
		session.playerLeft(name);
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (session.isFighting())
		{
			record(translator.varbitChanged(event));
		}
	}

	/** The contract name may be set after the interface loads, so it is read on the following ticks. */
	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		for (int componentId : ids.ids(Role.CONTRACT_NAME_WIDGET))
		{
			if (componentId >>> 16 == event.getGroupId())
			{
				pendingWidgetReads.add(componentId);
			}
		}
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		record(translator.gameObjectSpawned(event));
	}

	@Subscribe
	public void onGameObjectDespawned(GameObjectDespawned event)
	{
		record(translator.gameObjectDespawned(event));
	}

	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		if (session.isFighting())
		{
			record(translator.animationChanged(event));
		}
	}

	@Subscribe
	public void onGraphicChanged(GraphicChanged event)
	{
		if (session.isFighting())
		{
			record(translator.graphicChanged(event));
		}
	}

	@Subscribe
	public void onGraphicsObjectCreated(GraphicsObjectCreated event)
	{
		if (session.isFighting())
		{
			record(translator.graphicsObjectCreated(event));
		}
	}

	@Subscribe
	public void onProjectileMoved(ProjectileMoved event)
	{
		if (session.isFighting())
		{
			record(translator.projectileMoved(event));
		}
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (session.isFighting())
		{
			record(translator.hitsplatApplied(event));
		}
	}

	@Subscribe
	public void onOverheadTextChanged(OverheadTextChanged event)
	{
		if (session.isFighting())
		{
			record(translator.overheadTextChanged(event));
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (session.state() == SessionState.ARMED && event.getContainerId() == InventoryID.INV)
		{
			rememberContractItems(event.getItemContainer());
		}
		if (session.isFighting())
		{
			record(translator.itemContainerChanged(event));
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (session.isFighting())
		{
			record(translator.chatMessage(event));
		}
	}

	/** Keeps a raw log that is still being written when the client closes. */
	@Subscribe
	public void onClientShutdown(ClientShutdown event)
	{
		event.waitFor(pendingWrite.get());
	}

	private void startFightIfArmed()
	{
		session.updateRegion(inYamasDomain());
		Player self = client.getLocalPlayer();
		if (self == null || session.isFighting())
		{
			return;
		}
		String selfName = self.getName() == null ? "" : Text.sanitize(self.getName());
		List<String> present = players.keySet().stream().sorted().collect(Collectors.toList());
		session.yamaSpawned(new FightStart(selfName, positions.position(self.getLocalLocation()), present));
		if (session.isFighting())
		{
			Map<Integer, Integer> inventoryNow = currentInventory();
			translator.reset(inventoryNow);
			recordContractConsumedAtChallenge(inventoryNow);
		}
	}

	private void rememberContractItems(ItemContainer inventory)
	{
		for (int itemId : EventTranslator.aggregate(inventory.getItems()).keySet())
		{
			if (isContractItem(itemId))
			{
				contractItemsLastSeen.put(itemId, client.getTickCount());
			}
		}
	}

	private boolean isContractItem(int itemId)
	{
		for (Role role : Role.CONTRACT_ITEMS)
		{
			if (ids.is(role, itemId))
			{
				return true;
			}
		}
		return false;
	}

	/** A contract is consumed at the challenge, possibly before Yama spawns; record it as part of the fight. */
	private void recordContractConsumedAtChallenge(Map<Integer, Integer> inventoryNow)
	{
		int now = client.getTickCount();
		contractItemsLastSeen.forEach((itemId, lastSeen) ->
		{
			if (!inventoryNow.containsKey(itemId) && now - lastSeen <= CONTRACT_LOOKBACK_TICKS)
			{
				session.record(translator.contractConsumed(itemId));
			}
		});
		contractItemsLastSeen.clear();
	}

	private void readPendingWidgets()
	{
		for (Iterator<Integer> it = pendingWidgetReads.iterator(); it.hasNext(); )
		{
			int componentId = it.next();
			Widget widget = client.getWidget(componentId);
			if (widget != null && widget.getText() != null && !widget.getText().isEmpty())
			{
				record(translator.widgetText(componentId, Text.removeTags(widget.getText())));
				it.remove();
			}
		}
	}

	private void record(List<DomainEvent> events)
	{
		events.forEach(session::record);
	}

	private boolean inYamasDomain()
	{
		Player self = client.getLocalPlayer();
		return self != null && ids.is(Role.YAMAS_DOMAIN, positions.regionId(self.getLocalLocation()));
	}

	@Nullable
	private Player partner()
	{
		return session.partnerName().map(players::get).orElse(null);
	}

	private Map<Integer, Integer> currentInventory()
	{
		ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		return inventory == null ? Map.of() : EventTranslator.aggregate(inventory.getItems());
	}
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'com.yamareviewer.adapter.recording.GameEventListenerTest'`
Expected: PASS (10 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yamareviewer/adapter/recording/GameEventListener.java src/test/java/com/yamareviewer/adapter/recording/GameEventListenerTest.java
git commit -m "feat: receive RuneLite game events in one listener"
```

---

### Task 10: Plugin wiring

**Files:**
- Modify: `src/main/java/com/yamareviewer/YamaReviewerPlugin.java` (replace the Task 1 skeleton)

**Interfaces:**
- Consumes: everything from Tasks 1–9.
- Produces: the wired plugin. It owns the executor, registers `GameEventListener`, and has no `@Subscribe` methods.

- [ ] **Step 1: Wire the plugin**

Replace `src/main/java/com/yamareviewer/YamaReviewerPlugin.java`:

```java
package com.yamareviewer;

import com.google.gson.Gson;
import com.google.inject.Provides;
import com.yamareviewer.adapter.ids.BuiltInIds;
import com.yamareviewer.adapter.persistence.EventCodec;
import com.yamareviewer.adapter.persistence.FilepathFileStore;
import com.yamareviewer.adapter.persistence.GsonLogRepository;
import com.yamareviewer.adapter.recording.ActorResolver;
import com.yamareviewer.adapter.recording.EventTranslator;
import com.yamareviewer.adapter.recording.GameEventListener;
import com.yamareviewer.adapter.recording.ItemLookup;
import com.yamareviewer.adapter.recording.ItemManagerLookup;
import com.yamareviewer.adapter.recording.PositionReader;
import com.yamareviewer.adapter.recording.SnapshotReader;
import com.yamareviewer.adapter.recording.TickSampler;
import com.yamareviewer.application.command.KillSession;
import com.yamareviewer.application.handler.KillEndedHandler;
import com.yamareviewer.application.port.LogRepository;
import com.yamareviewer.domain.ids.IdRegistry;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.NpcUtil;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

/** Composition root: builds the object graph and registers the listener. No game logic lives here. */
@Slf4j
@PluginDescriptor(
	name = "Yama Reviewer",
	description = "Records Yama kills and reviews them after the kill ends. Silent during the fight.",
	tags = {"yama", "pvm", "review", "prayer", "duo"},
	internalName = "yama-reviewer"
)
public class YamaReviewerPlugin extends Plugin
{
	static final String VERSION = "0.1.0";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private EventBus eventBus;

	@Inject
	private YamaReviewerConfig config;

	@Inject
	private Gson gson;

	@Inject
	private ItemManager itemManager;

	@Inject
	private NpcUtil npcUtil;

	private ExecutorService executor;
	private KillSession session;
	private GameEventListener listener;

	@Provides
	YamaReviewerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(YamaReviewerConfig.class);
	}

	@Override
	protected void startUp() throws Exception
	{
		executor = Executors.newSingleThreadExecutor(runnable ->
		{
			Thread thread = new Thread(runnable, "yama-reviewer");
			thread.setDaemon(true);
			return thread;
		});
		IdRegistry ids = BuiltInIds.registry();
		LogRepository logs = new GsonLogRepository(new FilepathFileStore(getPluginDirectory()), new EventCodec(gson));
		KillEndedHandler handler = new KillEndedHandler(executor, logs, config::rawLogsKept);
		ItemLookup items = new ItemManagerLookup(itemManager);

		session = new KillSession(handler, new SnapshotReader(client, items), Clock.systemUTC(),
			() -> UUID.randomUUID().toString(), VERSION, ids.fingerprint(), config::captureMode);
		PositionReader positions = new PositionReader(client);
		ActorResolver actors = new ActorResolver(client, ids, session::partnerName);
		EventTranslator translator = new EventTranslator(ids, actors, session::currentTick, config::captureMode, items::name, positions);
		listener = new GameEventListener(client, ids, session, translator, new TickSampler(client, actors, positions),
			positions, npcUtil, handler::pending);

		eventBus.register(listener);
		log.debug("Yama Reviewer started");
	}

	@Override
	protected void shutDown()
	{
		eventBus.unregister(listener);
		KillSession ending = session;
		ExecutorService stopping = executor;
		clientThread.invoke(() ->
		{
			ending.leave();
			stopping.shutdown();
		});
		log.debug("Yama Reviewer stopped");
	}
}
```

`shutdown()` (not `shutdownNow()`) lets a raw log that `leave()` just queued finish in the background; neither call blocks.

- [ ] **Step 2: Run the whole test suite**

Run: `./gradlew test`
Expected: PASS (all tests so far).

- [ ] **Step 3: Check in game (the user does this; never automate game input)**

Run `./gradlew run`, log in following https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts, and enable **Yama Reviewer**.

Check:
1. Do one solo kill. Nothing appears on screen, in chat or as sound, during or after it.
2. A file appears in `~/.runelite/plugin-data/yama-reviewer/raw/`.
3. `./gradlew captureSummary --args="<that file>"` (Task 12) lists `YAMA` animations and `SELF` hitsplats, and the last line of the log is a `fight-end` with `YAMA_DIED`.
4. Teleport out mid-fight in a second attempt: a raw log ending in `LEFT` appears.
5. Log out mid-fight in a third attempt: a raw log ending in `LEFT` appears, without an `END` supplies snapshot.
6. Disable the plugin mid-fight in a fourth attempt: a raw log ending in `LEFT` appears.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/yamareviewer/YamaReviewerPlugin.java
git commit -m "feat: wire recording into the plugin"
```

---

### Task 11: Architecture rules

**Files:**
- Test: `src/test/java/com/yamareviewer/ArchitectureTest.java`

**Interfaces:**
- Consumes: the package layout of Tasks 1–10.
- Produces: build-failing rules that Parts 2–4 must keep passing.

- [ ] **Step 1: Write the rules**

`src/test/java/com/yamareviewer/ArchitectureTest.java`:

```java
package com.yamareviewer;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchUnitRunner;
import com.tngtech.archunit.lang.ArchRule;
import net.runelite.client.eventbus.Subscribe;
import org.junit.runner.RunWith;
import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@RunWith(ArchUnitRunner.class)
@AnalyzeClasses(packages = "com.yamareviewer", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureTest
{
	@ArchTest
	public static final ArchRule domainDependsOnNothingOutside = noClasses()
		.that().resideInAPackage("com.yamareviewer.domain..")
		.should().dependOnClassesThat().resideInAnyPackage(
			"com.yamareviewer.application..", "com.yamareviewer.adapter..",
			"net.runelite..", "com.google..", "javax.swing..", "java.awt..");

	@ArchTest
	public static final ArchRule applicationDoesNotKnowAdapters = noClasses()
		.that().resideInAPackage("com.yamareviewer.application..")
		.should().dependOnClassesThat().resideInAnyPackage(
			"com.yamareviewer.adapter..", "net.runelite..", "com.google..", "javax.swing..", "java.awt..");

	@ArchTest
	public static final ArchRule onlyRecordingSeesGameEvents = noClasses()
		.that().resideOutsideOfPackage("com.yamareviewer.adapter.recording..")
		.should().dependOnClassesThat().resideInAPackage("net.runelite.api.events..");

	@ArchTest
	public static final ArchRule onlyRecordingSubscribes = methods()
		.that().areAnnotatedWith(Subscribe.class)
		.should().beDeclaredInClassesThat().resideInAPackage("com.yamareviewer.adapter.recording..");

	@ArchTest
	public static final ArchRule recordingNeverReachesOutput = noClasses()
		.that().resideInAnyPackage(
			"com.yamareviewer.adapter.recording..", "com.yamareviewer.application.command..", "com.yamareviewer.domain..")
		.should().dependOnClassesThat().resideInAnyPackage("com.yamareviewer.adapter.publish..", "com.yamareviewer.adapter.ui..")
		.orShould().dependOnClassesThat().haveSimpleName("ReviewPublisher");

	@ArchTest
	public static final ArchRule onlyPublishTalksToChat = noClasses()
		.that().resideOutsideOfPackage("com.yamareviewer.adapter.publish..")
		.should().dependOnClassesThat().haveFullyQualifiedName("net.runelite.client.chat.ChatMessageManager");

	@ArchTest
	public static final ArchRule onlyUiAndRootUseSwing = noClasses()
		.that().resideOutsideOfPackages("com.yamareviewer.adapter.ui..", "com.yamareviewer")
		.should().dependOnClassesThat().resideInAnyPackage("javax.swing..", "net.runelite.client.ui..");

	@ArchTest
	public static final ArchRule noLiveCues = noClasses()
		.should().dependOnClassesThat().resideInAPackage("net.runelite.client.ui.overlay..")
		.orShould().dependOnClassesThat().haveFullyQualifiedName("net.runelite.client.Notifier")
		.orShould().dependOnClassesThat().resideInAPackage("javax.sound..")
		.orShould().callMethodWhere(target(name("playSoundEffect")));

	@ArchTest
	public static final ArchRule noUncheckedFileAccess = noClasses()
		.should().dependOnClassesThat().haveFullyQualifiedName("net.runelite.client.util.Filepath$Unchecked")
		.orShould().dependOnClassesThat().haveFullyQualifiedName("java.io.File")
		.orShould().dependOnClassesThat().haveFullyQualifiedName("java.nio.file.Files");
}
```

- [ ] **Step 2: Run the rules**

Run: `./gradlew test --tests 'com.yamareviewer.ArchitectureTest'`
Expected: PASS (9 rules). If a rule fails, fix the offending dependency in main code; never weaken a rule.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/yamareviewer/ArchitectureTest.java
git commit -m "test: enforce layering and the silent-during-the-fight rule"
```

---

### Task 12: Capture tools and the logging-kills guide

**Files:**
- Create: `src/test/java/com/yamareviewer/tools/GamevalNames.java`, `CaptureSummary.java`
- Test: `src/test/java/com/yamareviewer/tools/CaptureSummaryTest.java`
- Modify: `build.gradle` (add the `captureSummary` task)
- Create: `docs/logging-kills.md`

**Interfaces:**
- Consumes: `EventCodec` (Task 5), events (Task 2).
- Produces: `CaptureSummary.summarize(List<DomainEvent>, BiFunction<String, Integer, Optional<String>> names)`; `./gradlew captureSummary --args="<raw log .jsonl.gz>"`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/yamareviewer/tools/CaptureSummaryTest.java`:

```java
package com.yamareviewer.tools;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.GraphicObserved;
import java.util.List;
import java.util.Optional;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class CaptureSummaryTest
{
	@Test
	public void countsIdsPerActorWithTheirMostCommonGapAndGamevalName()
	{
		String summary = CaptureSummary.summarize(List.of(
			new AnimationObserved(0, Actor.YAMA, 42),
			new AnimationObserved(7, Actor.YAMA, 42),
			new AnimationObserved(14, Actor.YAMA, 42),
			new GraphicObserved(5, Actor.SELF, 3247)),
			(kind, id) -> id == 42 ? Optional.of("NPC_YAMA01_MAGIC01") : Optional.empty());

		assertTrue(summary, summary.contains(String.format(CaptureSummary.ROW, "animation", "YAMA", 42, 3, 0, 14, "7", "NPC_YAMA01_MAGIC01")));
		assertTrue(summary, summary.contains(String.format(CaptureSummary.ROW, "graphic", "SELF", 3247, 1, 5, 5, "-", "")));
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'com.yamareviewer.tools.CaptureSummaryTest'`
Expected: FAIL — `cannot find symbol: class CaptureSummary`.

- [ ] **Step 3: Write the tools**

`src/test/java/com/yamareviewer/tools/GamevalNames.java`:

```java
package com.yamareviewer.tools;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.ObjectID1;
import net.runelite.api.gameval.SpotanimID;

/** Development tool only (test source set, never shipped): gameval constant names by kind and id. */
final class GamevalNames
{
	private final Map<String, Map<Integer, String>> byKind = new HashMap<>();

	GamevalNames()
	{
		Map<Integer, String> animations = namesOf(AnimationID.class);
		Map<Integer, String> spotanims = namesOf(SpotanimID.class);
		Map<Integer, String> objects = new HashMap<>(namesOf(ObjectID.class));
		objects.putAll(namesOf(ObjectID1.class));
		byKind.put("animation", animations);
		byKind.put("object-animation", animations);
		byKind.put("graphic", spotanims);
		byKind.put("ground-graphic", spotanims);
		byKind.put("projectile", spotanims);
		byKind.put("npc", namesOf(NpcID.class));
		byKind.put("object", objects);
	}

	Optional<String> name(String kind, int id)
	{
		return Optional.ofNullable(byKind.getOrDefault(kind, Map.of()).get(id));
	}

	private static Map<Integer, String> namesOf(Class<?> constants)
	{
		Map<Integer, String> names = new HashMap<>();
		for (Field field : constants.getFields())
		{
			if (Modifier.isStatic(field.getModifiers()) && field.getType() == int.class)
			{
				try
				{
					names.putIfAbsent(field.getInt(null), field.getName());
				}
				catch (IllegalAccessException e)
				{
					throw new IllegalStateException(e);
				}
			}
		}
		return names;
	}
}
```

`src/test/java/com/yamareviewer/tools/CaptureSummary.java`:

```java
package com.yamareviewer.tools;

import com.google.gson.Gson;
import com.yamareviewer.adapter.persistence.EventCodec;
import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.event.GameMessageObserved;
import com.yamareviewer.domain.event.GraphicObserved;
import com.yamareviewer.domain.event.GroundGraphicObserved;
import com.yamareviewer.domain.event.HitsplatObserved;
import com.yamareviewer.domain.event.NpcSpawnObserved;
import com.yamareviewer.domain.event.ObjectAnimationObserved;
import com.yamareviewer.domain.event.ObjectSpawnObserved;
import com.yamareviewer.domain.event.OverheadTextObserved;
import com.yamareviewer.domain.event.ProjectileObserved;
import com.yamareviewer.domain.event.VarbitObserved;
import com.yamareviewer.domain.event.WidgetTextObserved;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.zip.GZIPInputStream;

/** Development tool: which IDs appeared in a raw log, how often, at what rhythm, and their gameval names. */
public final class CaptureSummary
{
	static final String ROW = "%-16s %-18s %9d %6d %6d %6d %5s  %s%n";

	public static void main(String[] args) throws IOException
	{
		if (args.length != 1)
		{
			System.err.println("Usage: ./gradlew captureSummary --args=\"<path to raw/*.jsonl.gz>\"");
			System.exit(1);
		}
		String text;
		try (InputStream in = new GZIPInputStream(Files.newInputStream(Path.of(args[0]))))
		{
			text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		EventCodec codec = new EventCodec(new Gson());
		String[] lines = text.split("\n");
		List<DomainEvent> events = new ArrayList<>();
		for (int i = 1; i < lines.length; i++)
		{
			if (!lines[i].isBlank())
			{
				codec.decode(lines[i]).ifPresent(events::add);
			}
		}
		GamevalNames names = new GamevalNames();
		System.out.print(summarize(events, names::name));
	}

	static String summarize(List<DomainEvent> events, BiFunction<String, Integer, Optional<String>> names)
	{
		Map<String, Row> rows = new TreeMap<>();
		for (DomainEvent event : events)
		{
			Row key = keyOf(event);
			if (key != null)
			{
				rows.computeIfAbsent(key.sortKey(), k -> key).ticks.add(event.getTick());
			}
		}
		StringBuilder out = new StringBuilder(String.format("%-16s %-18s %9s %6s %6s %6s %5s  %s%n",
			"event", "actor", "id", "count", "first", "last", "gap", "gameval"));
		for (Row row : rows.values())
		{
			List<Integer> ticks = row.ticks;
			out.append(String.format(ROW, row.kind, row.actor, row.id, ticks.size(), ticks.get(0),
				ticks.get(ticks.size() - 1), mostCommonGap(ticks), names.apply(row.kind, row.id).orElse("")));
		}
		return out.toString();
	}

	private static String mostCommonGap(List<Integer> ticks)
	{
		Map<Integer, Integer> gaps = new HashMap<>();
		for (int i = 1; i < ticks.size(); i++)
		{
			gaps.merge(ticks.get(i) - ticks.get(i - 1), 1, Integer::sum);
		}
		return gaps.entrySet().stream()
			.max(Map.Entry.<Integer, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
			.map(entry -> String.valueOf(entry.getKey()))
			.orElse("-");
	}

	private static Row keyOf(DomainEvent event)
	{
		if (event instanceof AnimationObserved)
		{
			AnimationObserved e = (AnimationObserved) event;
			return new Row("animation", label(e.getActor()), e.getAnimationId());
		}
		if (event instanceof GraphicObserved)
		{
			GraphicObserved e = (GraphicObserved) event;
			return new Row("graphic", label(e.getActor()), e.getGraphicId());
		}
		if (event instanceof GroundGraphicObserved)
		{
			return new Row("ground-graphic", "-", ((GroundGraphicObserved) event).getGraphicId());
		}
		if (event instanceof ProjectileObserved)
		{
			ProjectileObserved e = (ProjectileObserved) event;
			return new Row("projectile", "-> " + label(e.getTarget()), e.getProjectileId());
		}
		if (event instanceof NpcSpawnObserved)
		{
			NpcSpawnObserved e = (NpcSpawnObserved) event;
			return new Row("npc", label(e.getActor()), e.getNpcId());
		}
		if (event instanceof ObjectSpawnObserved)
		{
			return new Row("object", "-", ((ObjectSpawnObserved) event).getObjectId());
		}
		if (event instanceof ObjectAnimationObserved)
		{
			ObjectAnimationObserved e = (ObjectAnimationObserved) event;
			return new Row("object-animation", "object " + e.getObjectId(), e.getAnimationId());
		}
		if (event instanceof VarbitObserved)
		{
			VarbitObserved e = (VarbitObserved) event;
			return new Row("varbit", "value " + e.getValue(), e.getVarbitId());
		}
		if (event instanceof OverheadTextObserved)
		{
			OverheadTextObserved e = (OverheadTextObserved) event;
			return new Row("overhead", label(e.getActor()) + " " + e.getText(), 0);
		}
		if (event instanceof GameMessageObserved)
		{
			return new Row("game-message", ((GameMessageObserved) event).getText(), 0);
		}
		if (event instanceof WidgetTextObserved)
		{
			WidgetTextObserved e = (WidgetTextObserved) event;
			return new Row("widget-text", e.getText(), e.getComponentId());
		}
		if (event instanceof HitsplatObserved)
		{
			HitsplatObserved e = (HitsplatObserved) event;
			return new Row("hitsplat", label(e.getTarget()), e.getRawType());
		}
		return null;
	}

	private static String label(Actor actor)
	{
		if (actor == null)
		{
			return "tile";
		}
		if (actor.getName() != null)
		{
			return actor.getKind() + "(" + actor.getName() + ")";
		}
		return actor.getRef() >= 0 ? actor.getKind() + "(" + actor.getRef() + ")" : actor.getKind().toString();
	}

	private static final class Row
	{
		private final String kind;
		private final String actor;
		private final int id;
		private final List<Integer> ticks = new ArrayList<>();

		private Row(String kind, String actor, int id)
		{
			this.kind = kind;
			this.actor = actor;
			this.id = id;
		}

		private String sortKey()
		{
			return kind + "|" + actor + "|" + String.format("%09d", id);
		}
	}
}
```

- [ ] **Step 4: Add the Gradle task**

Append to `build.gradle`:

```groovy
tasks.register('captureSummary', JavaExec) {
	classpath = sourceSets.test.runtimeClasspath
	mainClass = 'com.yamareviewer.tools.CaptureSummary'
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests 'com.yamareviewer.tools.CaptureSummaryTest'`
Expected: PASS (1 test).

- [ ] **Step 6: Write the logging-kills guide**

`docs/logging-kills.md`:

```markdown
# Logging kills

Five kills with capture mode on confirm the ID mappings, the phase signals and the prayer-check tick
that Parts 2–4 rely on. Only you can do this: never automate game input.

## Setup

1. `./gradlew run`, then log in following https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts.
2. Enable **Yama Reviewer**. Under **Development**, turn on **Capture mode**.
3. Write down the real-world time when you do each deliberate action below.

## The kills

| Kill | Mode | Do deliberately |
| --- | --- | --- |
| 1 | Solo (Travel) | Pray correctly all of P3. Use every spec weapon you own at least once, including a purging staff spec on a flare. |
| 2 | Solo (Travel) | In P3: switch one prayer on the tick Yama casts, one on the tick the hit lands, pray the wrong style once, walk into one Shadow Crash line, get hit by one Shadow Wave, let one flare explode, stand next to Yama once to get meleed. Get hit by a Judge fire surge. |
| 3 | Duo host (Travel, partner joins after you) | Normal kill. Note who Yama targets in P3. |
| 4 | Duo joiner (Join) | Normal kill. |
| 5 | Any mode, under a contract | Normal kill. Note the contract's name. |

Raw logs land in `~/.runelite/plugin-data/yama-reviewer/raw/`. Copy the five files somewhere safe right
after each kill: only the newest 20 are kept.

## Reading a log

    ./gradlew captureSummary --args="$HOME/.runelite/plugin-data/yama-reviewer/raw/<file>.jsonl.gz"

Each row is one kind of event on one actor with one ID: count, first and last tick, most common gap,
and the gameval name when the ID has one. Check against spec section 5.5:

- **Attacks:** a `YAMA` animation with gap 8 before P3 and 7 in P3 (`YAMA_STANDARD_ATTACK`), and two
  `YAMA` graphics that alternate (`YAMA_CAST_MAGIC`/`_RANGED`), each followed by an impact graphic on
  `SELF` or `PARTNER`.
- **Prayer-check tick:** in kill 2, which of the two deliberate switches was scored as blocked decides
  `prayerCheck` (`CAST` or `HITSPLAT`).
- **Phases:** which of `varbit` `YAMA_TRANSITION_PHASE`, the overhead lines, the transition graphic and
  the Judge spawn fire at each Judge.
- **Glyphs:** whether glyphs appear as `object` spawns or only as `object-animation` rows, and which
  object ID is fire and which is shadow.
- **Crash lines:** ground graphics that come in groups of three on the same tick (`CRASH_FIREBALL`).
- **Still to capture:** the purging staff spec animation (`SELF` animation when you spec a flare) and
  the Judge fire surge NPC (`npc` row during a Judge phase).
- **Messages:** `game-message` rows: the Shadow Wave message "You've been injured and can't use
  protection prayers!" and the "Yama conjures" glyph message (purple for shadow, orange for fire).
- **Contract:** the `widget-text` row with the contract name, and an `inventory` event with change -1
  for the contract item at tick 0 (`zcat <file> | grep inventory`).

## Afterwards

1. Correct any mapping in `src/main/java/com/yamareviewer/adapter/ids/BuiltInIds.java` that the logs
   contradict, and fill the three roles still to capture.
2. Set `Rules.DEFAULT.prayerCheck` from kill 2.
3. Copy the five logs to `src/test/resources/fixtures/` for the golden tests in Part 3.
```

- [ ] **Step 7: Commit**

```bash
git add build.gradle src/test/java/com/yamareviewer/tools docs/logging-kills.md
git commit -m "feat: add the capture summary tool and the logging-kills guide"
```

---

## Self-Review

**Spec coverage (spec 13, part 1):** project setup (Task 1); domain events and stable type names of 5.1 (Task 2); roles, tunables and `gameval` built-in IDs of 5.5 (Task 3); fight lifecycle of 5.3 with the end completed on the next tick (Tasks 4, 9); raw log storage and schema policy of 5.2 (Task 5); the plugin executor and client-shutdown wait of 4.5 (Tasks 6, 9, 10); the anti-corruption layer (Tasks 7, 9); priced snapshots on the client thread (Task 8); the ArchUnit rules of 4.4 (Task 11); capture mode and the tools of 12 (Tasks 7, 12). The replay silence test through `GameEventListener` needs a `ReviewPublisher`, so it is in Part 2. Mode detection (5.4) and sections 6–9 are Parts 2–4.

**Placeholders:** none; the four roles with no built-in value are named in `BuiltInIds` and asserted in `BuiltInIdsTest`.

**Type consistency:** `KillEndedListener.killEnded(KillLog)`, `SnapshotSource.take(int, SnapshotKind)`, `LogRepository` (`save`, `loadAll`, `prune`), `FightStart(selfName, selfPosition, playersPresent)`, `PositionReader.position/regionId`, `EventTranslator.aggregate(Item[])` and `KillEndedHandler.pending()` are used identically in Tasks 4–12.
