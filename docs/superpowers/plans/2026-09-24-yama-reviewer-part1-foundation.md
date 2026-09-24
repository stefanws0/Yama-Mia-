# Yama Reviewer Part 1: Foundation and Capture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A RuneLite plugin that silently records every Yama kill as an append-only event stream on disk (plus an uncompressed capture file in capture mode), so the user can do the four logging kills that later parts depend on.

**Architecture:** Hexagonal layers under `com.yamareviewer`: `domain` (pure value objects, events, ID registry), `application` (the `KillSession` write side, ports, the kill-ended handler), `adapter` (RuneLite recording, Gson/Filepath persistence) and the root-package plugin class as the composition root. During a fight only raw observations are appended; nothing is classified or displayed. When the kill ends the frozen `KillLog` is saved off the client thread.

**Tech Stack:** Java 11 (release target), Gradle from the RuneLite example-plugin template, RuneLite client API (`latest.release`), Lombok, Gson (RuneLite's injected instance), JUnit 4.12, Mockito 5, ArchUnit 1.3.

**Spec:** `docs/superpowers/specs/2026-09-24-yama-reviewer-design.md` (sections 2, 4, 5, 13 part 1). Executors read the spec and this plan.

## Global Constraints

- Build must match the RuneLite example-plugin template structure; `options.release.set(11)`.
- Root package `com.yamareviewer`; `@PluginDescriptor(internalName = "yama-reviewer")`; config group `yamareviewer`.
- Nothing is drawn, printed or played during a fight. No `Overlay`, `InfoBox`, `Notifier`, sounds or chat messages in Part 1 at all.
- Record only inside region 6045 (role `YAMAS_DOMAIN`).
- All file I/O through RuneLite's `Filepath` via `Plugin.getPluginDirectory()`; never `java.io.File`/`Files` in main code; never `Filepath.Unchecked` anywhere.
- Use the injected `Gson` (`@Inject Gson`), never `new Gson()` in main code (tests may).
- No reflection in main code. `log.debug` for per-event logging.
- `domain` imports nothing from `application`, `adapter`, `net.runelite`, `com.google.gson`, `javax.swing`. `application` imports nothing from `adapter`, `net.runelite`, `com.google.gson`, `javax.swing`.
- No numeric game IDs in main code outside `src/main/resources/com/yamareviewer/ids.json`.
- RuneLite code style: tabs, braces on their own line.
- Building needs network access to `https://repo.runelite.net` and Maven Central. Run the commands on a machine that has it.

## Review Focus

1. Logging out, hopping or disabling the plugin mid-fight must keep the kill as `LEFT` without inventing an END snapshot from an empty inventory (KillSessionTest `leavingMidFight…`, `endSnapshotIsSkippedWhenUnavailable`; SnapshotReaderTest `notLoggedIn…`).
2. A second kill in the same instance after Yama dies must get a fresh kill id and tick counter (KillSessionTest `secondKillInSameInstance…`).
3. Yama's `NpcSpawned` firing again while fighting (scene reload) must not restart the kill; a duo partner who loads in after Yama appeared must still become the partner (KillSessionTest `spawnWhileFightingIsIgnored`, `partnerSeenMidFightBecomesPartner`).
4. Corrupt or foreign files in `raw/` must be skipped and renamed while the others still load (GsonLogRepositoryTest `corruptFileIsRenamedAndSkipped`).
5. A raw log written by a newer plugin version with unknown event types must still load, with the skip counted (GsonLogRepositoryTest `unknownEventTypesAreCountedNotFatal`).

---

## File Structure

```
build.gradle, settings.gradle, runelite-plugin.properties, gradlew, gradlew.bat, gradle/, .gitignore, LICENSE, README.md
src/main/java/com/yamareviewer/
  YamaReviewerPlugin.java          composition root: wiring + RuneLite event forwarding only
  YamaReviewerConfig.java          config (Part 1 keys: rawLogsKept, captureMode)
  domain/event/                    Actor, ActorKind, Position, enums, ItemStack, DomainEvent + 17 event classes
  domain/model/                    KillHeader, KillLog
  domain/ids/                      Role, RoleKind, PrayerCheck, TimingRules, IdRegistry
  application/port/                LogRepository, SnapshotSource
  application/command/             KillSession, SessionState, FightStart, KillEndedListener
  application/handler/             KillEndedHandler
  adapter/persistence/             FileStore, FilepathFileStore, EventCodec, IdsJsonLoader, GsonLogRepository
  adapter/recording/               ActorResolver, EventTranslator, TickSampler, SnapshotReader
src/main/resources/com/yamareviewer/ids.json
src/test/java/com/yamareviewer/
  YamaReviewerPluginTest.java      dev-client launcher (template convention, not a unit test)
  ArchitectureTest.java
  domain/…, application/…, adapter/… tests mirroring main
  tools/CaptureSummary.java        dev tool for the logging kills
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
		description = "Also write an uncompressed log with every observed ID, for filling ids.json",
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
- Create in `src/main/java/com/yamareviewer/domain/event/`: `ActorKind.java`, `Actor.java`, `Position.java`, `EntryChoice.java`, `EndReason.java`, `ProtectionPrayer.java`, `HitsplatKind.java`, `SnapshotKind.java`, `ItemStack.java`, `DomainEvent.java`, and the events `EntryChosen`, `FightStarted`, `FightEnded`, `PartnerSeen`, `NpcSpawnObserved`, `NpcDespawnObserved`, `ObjectSpawnObserved`, `OverheadTextObserved`, `ScriptObserved`, `AnimationObserved`, `GraphicObserved`, `GroundGraphicObserved`, `ProjectileObserved`, `HitsplatObserved`, `TickState`, `InventoryDelta`, `SuppliesSnapshot`
- Create: `src/main/java/com/yamareviewer/domain/model/KillHeader.java`, `KillLog.java`
- Test: `src/test/java/com/yamareviewer/domain/event/ActorTest.java`, `src/test/java/com/yamareviewer/domain/model/KillLogTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: every type listed above with Lombok `@Value` getters (`getTick()`, `getActor()`, …); `Actor.SELF/PARTNER/YAMA/JUDGE`, `Actor.flare(int)`, `Actor.npc(int)`, `Actor.other(String)`, `Actor.isPlayer()`; `KillLog.of(KillHeader, List<? extends DomainEvent>, int)`, `KillLog.eventsOf(Class<T>)`, `KillLog.endReason()`, `KillLog.lastTick()`, `KillLog.SCHEMA_VERSION = 1`.

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

`src/test/java/com/yamareviewer/domain/model/KillLogTest.java`:

```java
package com.yamareviewer.domain.model;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.EntryChoice;
import com.yamareviewer.domain.event.FightEnded;
import com.yamareviewer.domain.event.HitsplatKind;
import com.yamareviewer.domain.event.HitsplatObserved;
import com.yamareviewer.domain.event.ScriptObserved;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class KillLogTest
{
	private static KillHeader header()
	{
		return new KillHeader("kill-1", 1_000L, 2_000L, "0.1.0", KillLog.SCHEMA_VERSION, 1, EntryChoice.TRAVEL, null, false);
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
		KillLog log = KillLog.of(header(), List.of(new ScriptObserved(0, 1)), 0);

		log.getEvents().add(new ScriptObserved(1, 1));
	}

	@Test
	public void laterChangesToTheSourceListDoNotLeakIn()
	{
		List<DomainEvent> source = new ArrayList<>();
		KillLog log = KillLog.of(header(), source, 0);

		source.add(new ScriptObserved(1, 1));

		assertTrue(log.getEvents().isEmpty());
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'com.yamareviewer.domain.*'`
Expected: FAIL — compilation errors, `cannot find symbol` for `Actor`, `KillLog` and the event classes.

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

/** A world position; inside the instance it is the template (real-world) coordinate. */
@Value
public class Position
{
	int x;
	int y;
	int plane;
}
```

`EntryChoice.java`, `EndReason.java`, `ProtectionPrayer.java`, `HitsplatKind.java`, `SnapshotKind.java`:

```java
package com.yamareviewer.domain.event;

public enum EntryChoice
{
	TRAVEL,
	JOIN,
	UNKNOWN
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

`ItemStack.java`:

```java
package com.yamareviewer.domain.event;

import lombok.Value;

@Value
public class ItemStack
{
	int itemId;
	String name;
	int quantity;
}
```

- [ ] **Step 4: Write `DomainEvent` and the events**

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

import java.util.List;
import lombok.Value;

@Value
public class FightStarted implements DomainEvent
{
	int tick;
	String selfName;
	List<String> otherPlayers;
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

/** Another player appeared in the arena after the fight had started (a duo partner loading in late). */
@Value
public class PartnerSeen implements DomainEvent
{
	int tick;
	String name;
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

@Value
public class ScriptObserved implements DomainEvent
{
	int tick;
	int scriptId;
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

/** State at the end of a tick, recorded after every other event of that tick. */
@Value
public class TickState implements DomainEvent
{
	int tick;
	Set<ProtectionPrayer> prayers;
	int hitpoints;
	int prayerPoints;
	int specEnergy;
	/** -1 when nothing is wielded. */
	int weaponId;
	/** Null when Yama targets nobody. */
	Actor yamaTarget;
	Position selfPosition;
	/** Null in solo or when the partner is not visible. */
	Position partnerPosition;
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
	List<ItemStack> items;
}
```

- [ ] **Step 5: Write `KillHeader` and `KillLog`**

`src/main/java/com/yamareviewer/domain/model/KillHeader.java`:

```java
package com.yamareviewer.domain.model;

import com.yamareviewer.domain.event.EntryChoice;
import lombok.Value;

@Value
public class KillHeader
{
	String killId;
	long startEpochMs;
	long endEpochMs;
	String pluginVersion;
	int schemaVersion;
	int idsVersion;
	EntryChoice entryChoice;
	/** Null in solo. Stored locally only. */
	String partnerName;
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
	/** Events that could not be read back (unknown type from another plugin version). */
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
Expected: PASS (8 tests).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/yamareviewer/domain src/test/java/com/yamareviewer/domain
git commit -m "feat: add domain events and the immutable kill log"
```

---

### Task 3: ID registry and ids.json

**Files:**
- Create: `src/main/java/com/yamareviewer/domain/ids/RoleKind.java`, `Role.java`, `PrayerCheck.java`, `TimingRules.java`, `IdRegistry.java`
- Create: `src/main/resources/com/yamareviewer/ids.json`
- Create: `src/main/java/com/yamareviewer/adapter/persistence/IdsJsonLoader.java`
- Test: `src/test/java/com/yamareviewer/domain/ids/IdRegistryTest.java`, `src/test/java/com/yamareviewer/adapter/persistence/IdsJsonLoaderTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `Role` (enum, `kind()`), `RoleKind` (enum, `jsonGroup()`), `TimingRules(PrayerCheck prayerCheck, int prayerCheckOffset, int crashImpactWindow, int specResultWindow)` with `TimingRules.DEFAULT`; `IdRegistry(int version, Map<Role, Set<Integer>> ids, Map<Role, Set<String>> texts, TimingRules timing)` with `version()`, `timing()`, `ids(Role)`, `texts(Role)`, `is(Role, int)`, `matchesText(Role, String)`, `isCaptured(Role)`, `roleOf(RoleKind, int)`, `withOverrides(Map<Role, Set<Integer>>)`; `IdsJsonLoader(Gson)` with `loadBundled()` and `parse(Reader)`.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/yamareviewer/domain/ids/IdRegistryTest.java`:

```java
package com.yamareviewer.domain.ids;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class IdRegistryTest
{
	private final IdRegistry registry = new IdRegistry(
		3,
		Map.of(
			Role.YAMA, Set.of(100),
			Role.YAMA_STANDARD_ATTACK, Set.of(200, 201),
			Role.P3_MAGIC_ON_PLAYER, Set.of()),
		Map.of(Role.PHASE_TRANSITION_TEXT, Set.of("Enough.")),
		TimingRules.DEFAULT);

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
		assertFalse(registry.isCaptured(Role.P3_MAGIC_ON_PLAYER));
		assertFalse(registry.isCaptured(Role.SHADOW_CRASH));
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
	public void overridesReplaceOnlyTheirOwnRoles()
	{
		IdRegistry patched = registry.withOverrides(Map.of(Role.P3_MAGIC_ON_PLAYER, Set.of(300)));

		assertTrue(patched.is(Role.P3_MAGIC_ON_PLAYER, 300));
		assertTrue(patched.is(Role.YAMA, 100));
		assertFalse(registry.isCaptured(Role.P3_MAGIC_ON_PLAYER));
		assertEquals(3, patched.version());
	}

	@Test(expected = UnsupportedOperationException.class)
	public void idSetsCannotBeModified()
	{
		registry.ids(Role.YAMA).add(5);
	}
}
```

`src/test/java/com/yamareviewer/adapter/persistence/IdsJsonLoaderTest.java`:

```java
package com.yamareviewer.adapter.persistence;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.PrayerCheck;
import com.yamareviewer.domain.ids.Role;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import net.runelite.api.gameval.NpcID;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class IdsJsonLoaderTest
{
	private static final String TIMING = "{\"prayerCheck\":\"HITSPLAT\",\"prayerCheckOffset\":0,\"crashImpactWindow\":1,\"specResultWindow\":6}";

	private final IdsJsonLoader loader = new IdsJsonLoader(new Gson());

	@Test
	public void bundledFileHasTheKnownIds()
	{
		IdRegistry ids = loader.loadBundled();

		assertTrue(ids.is(Role.YAMA, 14176));
		assertTrue(ids.is(Role.YAMAS_DOMAIN, 6045));
		assertTrue(ids.is(Role.PHASE_TRANSITION_SCRIPT, 948));
		assertTrue(ids.matchesText(Role.PHASE_TRANSITION_TEXT, "You bore me."));
		assertFalse(ids.isCaptured(Role.P3_MAGIC_ON_PLAYER));
		assertEquals(PrayerCheck.HITSPLAT, ids.timing().getPrayerCheck());
		assertEquals(1, ids.version());
	}

	@Test
	public void bundledNpcIdsMatchRuneLiteGameVals()
	{
		IdRegistry ids = loader.loadBundled();

		assertEquals(Set.of(NpcID.YAMA), ids.ids(Role.YAMA));
		assertEquals(Set.of(NpcID.YAMA_JUDGE_OF_YAMA), ids.ids(Role.JUDGE));
		assertEquals(Set.of(NpcID.YAMA_VOIDFLARE), ids.ids(Role.VOID_FLARE));
		assertEquals(Set.of(NpcID.YAMA_METEOR_NPC), ids.ids(Role.METEOR_NPC));
		assertEquals(Set.of(NpcID.VOICE_OF_YAMA_1OP, NpcID.VOICE_OF_YAMA_2OP, NpcID.VOICE_OF_YAMA_3OP), ids.ids(Role.VOICE_OF_YAMA));
	}

	@Test
	public void bundledFileListsEveryRole() throws IOException
	{
		JsonObject root;
		try (Reader reader = new InputStreamReader(IdsJsonLoader.class.getResourceAsStream(IdsJsonLoader.BUNDLED), StandardCharsets.UTF_8))
		{
			root = new Gson().fromJson(reader, JsonObject.class);
		}

		for (Role role : Role.values())
		{
			assertTrue(role + " is missing from ids.json", root.getAsJsonObject(role.kind().jsonGroup()).has(role.name()));
		}
	}

	@Test(expected = IllegalStateException.class)
	public void roleUnderTheWrongGroupFails()
	{
		loader.parse(new StringReader("{\"version\":1,\"graphics\":{\"YAMA\":[1]},\"timing\":" + TIMING + "}"));
	}

	@Test(expected = IllegalArgumentException.class)
	public void unknownRoleFails()
	{
		loader.parse(new StringReader("{\"version\":1,\"npcs\":{\"NOT_A_ROLE\":[1]},\"timing\":" + TIMING + "}"));
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'com.yamareviewer.domain.ids.*' --tests 'com.yamareviewer.adapter.persistence.IdsJsonLoaderTest'`
Expected: FAIL — `cannot find symbol` for `IdRegistry`, `Role`, `IdsJsonLoader`.

- [ ] **Step 3: Write the ID types**

`RoleKind.java`:

```java
package com.yamareviewer.domain.ids;

public enum RoleKind
{
	NPC("npcs"),
	REGION("regions"),
	SCRIPT("scripts"),
	OVERHEAD("overheads"),
	OBJECT("objects"),
	ANIMATION("animations"),
	GRAPHIC("graphics"),
	PROJECTILE("projectiles"),
	ITEM("items");

	private final String jsonGroup;

	RoleKind(String jsonGroup)
	{
		this.jsonGroup = jsonGroup;
	}

	public String jsonGroup()
	{
		return jsonGroup;
	}
}
```

`Role.java`:

```java
package com.yamareviewer.domain.ids;

/** Everything the plugin needs to recognise, by what it means rather than by number. */
public enum Role
{
	YAMA(RoleKind.NPC),
	JUDGE(RoleKind.NPC),
	VOID_FLARE(RoleKind.NPC),
	VOICE_OF_YAMA(RoleKind.NPC),
	METEOR_NPC(RoleKind.NPC),

	YAMAS_DOMAIN(RoleKind.REGION),

	PHASE_TRANSITION_SCRIPT(RoleKind.SCRIPT),
	PHASE_TRANSITION_TEXT(RoleKind.OVERHEAD),

	GLYPH_FIRE(RoleKind.OBJECT),
	GLYPH_SHADOW(RoleKind.OBJECT),

	YAMA_STANDARD_ATTACK(RoleKind.ANIMATION),
	YAMA_MELEE(RoleKind.ANIMATION),
	YAMA_SNAP(RoleKind.ANIMATION),
	SHADOW_STOMP(RoleKind.ANIMATION),
	SPEC_EMBERLIGHT(RoleKind.ANIMATION),
	SPEC_ELDER_MAUL(RoleKind.ANIMATION),
	SPEC_DRAGON_WARHAMMER(RoleKind.ANIMATION),
	SPEC_BANDOS_GODSWORD(RoleKind.ANIMATION),
	SPEC_ACCURSED_SCEPTRE(RoleKind.ANIMATION),
	SPEC_EYE_OF_AYAK(RoleKind.ANIMATION),
	SPEC_SOULFLAME_HORN(RoleKind.ANIMATION),
	SPEC_PURGING_STAFF(RoleKind.ANIMATION),
	SPEC_SARADOMIN_GODSWORD(RoleKind.ANIMATION),

	P3_MAGIC_ON_PLAYER(RoleKind.GRAPHIC),
	P3_RANGED_ON_PLAYER(RoleKind.GRAPHIC),
	SHADOW_CRASH(RoleKind.GRAPHIC),
	FLARE_EXPLOSION(RoleKind.GRAPHIC),
	GLYPH_SPECIAL(RoleKind.GRAPHIC),
	METEOR_STRIKE(RoleKind.GRAPHIC),

	WEAPON_EMBERLIGHT(RoleKind.ITEM),
	WEAPON_ELDER_MAUL(RoleKind.ITEM),
	WEAPON_DRAGON_WARHAMMER(RoleKind.ITEM),
	WEAPON_BANDOS_GODSWORD(RoleKind.ITEM),
	WEAPON_ACCURSED_SCEPTRE(RoleKind.ITEM),
	WEAPON_EYE_OF_AYAK(RoleKind.ITEM),
	WEAPON_SOULFLAME_HORN(RoleKind.ITEM),
	WEAPON_PURGING_STAFF(RoleKind.ITEM),
	WEAPON_SARADOMIN_GODSWORD(RoleKind.ITEM);

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

/** Which tick counts as an attack "landing" for the prayer check (spec 6.3). */
public enum PrayerCheck
{
	SNAP,
	HITSPLAT
}
```

`TimingRules.java`:

```java
package com.yamareviewer.domain.ids;

import lombok.Value;

@Value
public class TimingRules
{
	public static final TimingRules DEFAULT = new TimingRules(PrayerCheck.HITSPLAT, 0, 1, 6);

	PrayerCheck prayerCheck;
	int prayerCheckOffset;
	int crashImpactWindow;
	int specResultWindow;
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
 * All game IDs by role. Built from ids.json, with local overrides layered on top (Part 4).
 * A role with no IDs is "uncaptured" and every section that needs it is hidden.
 */
public final class IdRegistry
{
	private final int version;
	private final Map<Role, Set<Integer>> ids;
	private final Map<Role, Set<String>> texts;
	private final TimingRules timing;

	public IdRegistry(int version, Map<Role, Set<Integer>> ids, Map<Role, Set<String>> texts, TimingRules timing)
	{
		this.version = version;
		this.ids = copy(ids);
		this.texts = copy(texts);
		this.timing = timing;
	}

	public int version()
	{
		return version;
	}

	public TimingRules timing()
	{
		return timing;
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

	public boolean matchesText(Role role, String text)
	{
		return text != null && texts(role).contains(text);
	}

	public boolean isCaptured(Role role)
	{
		return role.kind() == RoleKind.OVERHEAD ? !texts(role).isEmpty() : !ids(role).isEmpty();
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

	public IdRegistry withOverrides(Map<Role, Set<Integer>> overrides)
	{
		Map<Role, Set<Integer>> merged = new EnumMap<>(Role.class);
		merged.putAll(ids);
		merged.putAll(overrides);
		return new IdRegistry(version, merged, texts, timing);
	}

	private static <T> Map<Role, Set<T>> copy(Map<Role, Set<T>> source)
	{
		Map<Role, Set<T>> copy = new EnumMap<>(Role.class);
		source.forEach((role, values) -> copy.put(role, Set.copyOf(values)));
		return Collections.unmodifiableMap(copy);
	}
}
```

- [ ] **Step 4: Write `ids.json`**

`src/main/resources/com/yamareviewer/ids.json` (every role listed; empty means uncaptured):

```json
{
  "version": 1,
  "npcs": {
    "YAMA": [14176],
    "JUDGE": [14180],
    "VOID_FLARE": [14179],
    "VOICE_OF_YAMA": [14183, 14184, 14185],
    "METEOR_NPC": [14182]
  },
  "regions": {
    "YAMAS_DOMAIN": [6045]
  },
  "scripts": {
    "PHASE_TRANSITION_SCRIPT": [948]
  },
  "overheads": {
    "PHASE_TRANSITION_TEXT": ["Begone", "You bore me.", "Enough."]
  },
  "objects": {
    "GLYPH_FIRE": [],
    "GLYPH_SHADOW": []
  },
  "animations": {
    "YAMA_STANDARD_ATTACK": [],
    "YAMA_MELEE": [],
    "YAMA_SNAP": [],
    "SHADOW_STOMP": [],
    "SPEC_EMBERLIGHT": [],
    "SPEC_ELDER_MAUL": [],
    "SPEC_DRAGON_WARHAMMER": [],
    "SPEC_BANDOS_GODSWORD": [],
    "SPEC_ACCURSED_SCEPTRE": [],
    "SPEC_EYE_OF_AYAK": [],
    "SPEC_SOULFLAME_HORN": [],
    "SPEC_PURGING_STAFF": [],
    "SPEC_SARADOMIN_GODSWORD": []
  },
  "graphics": {
    "P3_MAGIC_ON_PLAYER": [],
    "P3_RANGED_ON_PLAYER": [],
    "SHADOW_CRASH": [],
    "FLARE_EXPLOSION": [],
    "GLYPH_SPECIAL": [],
    "METEOR_STRIKE": []
  },
  "projectiles": {},
  "items": {
    "WEAPON_EMBERLIGHT": [],
    "WEAPON_ELDER_MAUL": [],
    "WEAPON_DRAGON_WARHAMMER": [],
    "WEAPON_BANDOS_GODSWORD": [],
    "WEAPON_ACCURSED_SCEPTRE": [],
    "WEAPON_EYE_OF_AYAK": [],
    "WEAPON_SOULFLAME_HORN": [],
    "WEAPON_PURGING_STAFF": [],
    "WEAPON_SARADOMIN_GODSWORD": []
  },
  "timing": {
    "prayerCheck": "HITSPLAT",
    "prayerCheckOffset": 0,
    "crashImpactWindow": 1,
    "specResultWindow": 6
  }
}
```

- [ ] **Step 5: Write `IdsJsonLoader`**

`src/main/java/com/yamareviewer/adapter/persistence/IdsJsonLoader.java`:

```java
package com.yamareviewer.adapter.persistence;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.PrayerCheck;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.RoleKind;
import com.yamareviewer.domain.ids.TimingRules;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class IdsJsonLoader
{
	static final String BUNDLED = "/com/yamareviewer/ids.json";

	private final Gson gson;

	public IdsJsonLoader(Gson gson)
	{
		this.gson = gson;
	}

	public IdRegistry loadBundled()
	{
		try (InputStream in = IdsJsonLoader.class.getResourceAsStream(BUNDLED))
		{
			if (in == null)
			{
				throw new IllegalStateException("Missing " + BUNDLED);
			}
			return parse(new InputStreamReader(in, StandardCharsets.UTF_8));
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	/** Fails fast on unknown roles or roles filed under the wrong kind: the file ships with the plugin. */
	public IdRegistry parse(Reader reader)
	{
		JsonObject root = gson.fromJson(reader, JsonObject.class);
		Map<Role, Set<Integer>> ids = new EnumMap<>(Role.class);
		Map<Role, Set<String>> texts = new EnumMap<>(Role.class);

		for (RoleKind kind : RoleKind.values())
		{
			if (!root.has(kind.jsonGroup()))
			{
				continue;
			}
			for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject(kind.jsonGroup()).entrySet())
			{
				Role role = Role.valueOf(entry.getKey());
				if (role.kind() != kind)
				{
					throw new IllegalStateException(role + " is a " + role.kind() + " role but is listed under " + kind.jsonGroup());
				}
				if (kind == RoleKind.OVERHEAD)
				{
					Set<String> values = new LinkedHashSet<>();
					entry.getValue().getAsJsonArray().forEach(value -> values.add(value.getAsString()));
					texts.put(role, values);
				}
				else
				{
					Set<Integer> values = new LinkedHashSet<>();
					entry.getValue().getAsJsonArray().forEach(value -> values.add(value.getAsInt()));
					ids.put(role, values);
				}
			}
		}

		JsonObject timing = root.getAsJsonObject("timing");
		TimingRules rules = new TimingRules(
			PrayerCheck.valueOf(timing.get("prayerCheck").getAsString()),
			timing.get("prayerCheckOffset").getAsInt(),
			timing.get("crashImpactWindow").getAsInt(),
			timing.get("specResultWindow").getAsInt());

		return new IdRegistry(root.get("version").getAsInt(), ids, texts, rules);
	}
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.yamareviewer.domain.ids.*' --tests 'com.yamareviewer.adapter.persistence.IdsJsonLoaderTest'`
Expected: PASS (11 tests).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/yamareviewer/domain/ids src/main/resources src/main/java/com/yamareviewer/adapter/persistence/IdsJsonLoader.java src/test/java/com/yamareviewer/domain/ids src/test/java/com/yamareviewer/adapter/persistence/IdsJsonLoaderTest.java
git commit -m "feat: add the ID registry loaded from ids.json"
```

---

### Task 4: KillSession, the write side

**Files:**
- Create: `src/main/java/com/yamareviewer/application/port/SnapshotSource.java`
- Create: `src/main/java/com/yamareviewer/application/command/KillEndedListener.java`, `SessionState.java`, `FightStart.java`, `KillSession.java`
- Test: `src/test/java/com/yamareviewer/application/command/KillSessionTest.java`

**Interfaces:**
- Consumes: Task 2 events and `KillLog`/`KillHeader`.
- Produces: `SnapshotSource { Optional<SuppliesSnapshot> take(int tick, SnapshotKind kind); }`; `KillEndedListener { void killEnded(KillLog kill); }`; `FightStart(String selfName, List<String> otherPlayers, Position selfPosition)`; `KillSession(KillEndedListener, SnapshotSource, Clock, Supplier<String> killIds, String pluginVersion, int idsVersion, BooleanSupplier captureMode)` with the commands `chooseEntry(EntryChoice)`, `updateRegion(boolean)`, `yamaSpawned(FightStart)`, `partnerSeen(String)`, `record(DomainEvent)`, `endTick()`, `yamaDied()`, `playerDied()`, `leave()` and the queries `state()`, `isFighting()`, `currentTick()`, `partnerName()`.

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
import com.yamareviewer.domain.event.FightEnded;
import com.yamareviewer.domain.event.FightStarted;
import com.yamareviewer.domain.event.HitsplatKind;
import com.yamareviewer.domain.event.HitsplatObserved;
import com.yamareviewer.domain.event.PartnerSeen;
import com.yamareviewer.domain.event.Position;
import com.yamareviewer.domain.event.ScriptObserved;
import com.yamareviewer.domain.event.SnapshotKind;
import com.yamareviewer.domain.event.SuppliesSnapshot;
import com.yamareviewer.domain.model.KillLog;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
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
		session = new KillSession(ended::add, snapshots, clock, () -> "kill-" + killCounter.incrementAndGet(), "0.1.0", 7, () -> capture);
	}

	private static FightStart solo()
	{
		return new FightStart("Me", List.of(), new Position(1, 2, 0));
	}

	private static List<String> types(KillLog kill)
	{
		return kill.getEvents().stream().map(e -> e.getClass().getSimpleName()).collect(toList());
	}

	@Test
	public void recordsNothingOutsideTheRegion()
	{
		session.yamaSpawned(solo());
		session.record(new ScriptObserved(0, 1));
		session.yamaDied();

		assertEquals(SessionState.IDLE, session.state());
		assertTrue(ended.isEmpty());
	}

	@Test
	public void fullKillProducesAnOrderedLog()
	{
		session.chooseEntry(EntryChoice.TRAVEL);
		session.updateRegion(true);
		session.yamaSpawned(solo());
		session.record(new AnimationObserved(0, Actor.YAMA, 10));
		session.endTick();
		session.record(new HitsplatObserved(1, Actor.SELF, HitsplatKind.DAMAGE, 5, 1, false));
		session.yamaDied();

		assertEquals(1, ended.size());
		KillLog kill = ended.get(0);
		assertEquals(List.of("EntryChosen", "FightStarted", "SuppliesSnapshot", "AnimationObserved",
			"HitsplatObserved", "SuppliesSnapshot", "FightEnded"), types(kill));
		assertEquals(EntryChoice.TRAVEL, kill.getHeader().getEntryChoice());
		assertEquals("kill-1", kill.getHeader().getKillId());
		assertEquals(7, kill.getHeader().getIdsVersion());
		assertEquals(5_000L, kill.getHeader().getStartEpochMs());
		assertEquals(EndReason.YAMA_DIED, kill.endReason());
		assertEquals(1, kill.lastTick());
		assertEquals(SessionState.ARMED, session.state());
	}

	@Test
	public void deathTakesTheEndSnapshotBeforeFightEnded()
	{
		session.updateRegion(true);
		session.yamaSpawned(solo());
		session.endTick();
		session.playerDied();

		List<DomainEvent> events = ended.get(0).getEvents();
		SuppliesSnapshot end = (SuppliesSnapshot) events.get(events.size() - 2);
		FightEnded fightEnded = (FightEnded) events.get(events.size() - 1);
		assertEquals(SnapshotKind.END, end.getKind());
		assertEquals(fightEnded.getTick(), end.getTick());
		assertEquals(EndReason.PLAYER_DIED, fightEnded.getReason());
	}

	@Test
	public void leavingMidFightEndsAsLeftAndForgetsTheEntryChoice()
	{
		session.chooseEntry(EntryChoice.JOIN);
		session.updateRegion(true);
		session.yamaSpawned(solo());
		session.updateRegion(false);

		assertEquals(EndReason.LEFT, ended.get(0).endReason());
		assertEquals(EntryChoice.JOIN, ended.get(0).getHeader().getEntryChoice());
		assertEquals(SessionState.IDLE, session.state());

		session.updateRegion(true);
		session.yamaSpawned(solo());
		session.leave();
		assertEquals(EntryChoice.UNKNOWN, ended.get(1).getHeader().getEntryChoice());
	}

	@Test
	public void endSnapshotIsSkippedWhenUnavailable()
	{
		session.updateRegion(true);
		session.yamaSpawned(solo());
		snapshots.available = false;
		session.leave();

		assertEquals(List.of("EntryChosen", "FightStarted", "SuppliesSnapshot", "FightEnded"), types(ended.get(0)));
	}

	@Test
	public void secondKillInSameInstanceGetsAFreshIdAndTicks()
	{
		session.updateRegion(true);
		session.yamaSpawned(solo());
		session.endTick();
		session.endTick();
		session.endTick();
		session.yamaDied();

		session.yamaSpawned(solo());
		session.endTick();
		session.yamaDied();

		assertEquals(2, ended.size());
		assertEquals("kill-2", ended.get(1).getHeader().getKillId());
		assertEquals(0, ended.get(1).eventsOf(FightStarted.class).get(0).getTick());
		assertEquals(1, ended.get(1).lastTick());
	}

	@Test
	public void spawnWhileFightingIsIgnored()
	{
		session.updateRegion(true);
		session.yamaSpawned(solo());
		session.endTick();
		session.yamaSpawned(new FightStart("Me", List.of("Someone"), new Position(9, 9, 0)));
		session.yamaDied();

		assertEquals(1, ended.get(0).eventsOf(FightStarted.class).size());
		assertEquals(1, ended.get(0).lastTick());
		assertNull(ended.get(0).getHeader().getPartnerName());
	}

	@Test
	public void firstOtherPlayerIsThePartnerAndCaptureIsStored()
	{
		capture = true;
		session.updateRegion(true);
		session.yamaSpawned(new FightStart("Me", List.of("Buddy"), new Position(1, 2, 0)));

		assertEquals(Optional.of("Buddy"), session.partnerName());
		session.yamaDied();
		assertEquals("Buddy", ended.get(0).getHeader().getPartnerName());
		assertTrue(ended.get(0).getHeader().isCapture());
	}

	@Test
	public void partnerSeenMidFightBecomesPartner()
	{
		session.updateRegion(true);
		session.yamaSpawned(solo());
		session.endTick();
		session.partnerSeen("Late");
		session.partnerSeen("Third");
		session.yamaDied();

		KillLog kill = ended.get(0);
		assertEquals("Late", kill.getHeader().getPartnerName());
		List<PartnerSeen> seen = kill.eventsOf(PartnerSeen.class);
		assertEquals(1, seen.size());
		assertEquals(1, seen.get(0).getTick());
	}

	@Test
	public void entryChoiceDuringAFightIsIgnored()
	{
		session.chooseEntry(EntryChoice.TRAVEL);
		session.updateRegion(true);
		session.yamaSpawned(solo());
		session.chooseEntry(EntryChoice.JOIN);
		session.yamaDied();

		assertEquals(EntryChoice.TRAVEL, ended.get(0).getHeader().getEntryChoice());
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
	List<String> otherPlayers;
	Position selfPosition;
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
import com.yamareviewer.domain.event.PartnerSeen;
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
 * The write side. Its public methods are the commands; the only thing it produces is a
 * frozen {@link KillLog}, handed to the {@link KillEndedListener} when the kill ends.
 * Not thread-safe: call it from the client thread only.
 */
public final class KillSession
{
	private final KillEndedListener listener;
	private final SnapshotSource snapshots;
	private final Clock clock;
	private final Supplier<String> killIds;
	private final String pluginVersion;
	private final int idsVersion;
	private final BooleanSupplier captureMode;

	private final List<DomainEvent> events = new ArrayList<>();
	private SessionState state = SessionState.IDLE;
	private EntryChoice entryChoice = EntryChoice.UNKNOWN;
	private int tick;
	private long startEpochMs;
	private String partnerName;
	private boolean capture;

	public KillSession(KillEndedListener listener, SnapshotSource snapshots, Clock clock, Supplier<String> killIds,
		String pluginVersion, int idsVersion, BooleanSupplier captureMode)
	{
		this.listener = listener;
		this.snapshots = snapshots;
		this.clock = clock;
		this.killIds = killIds;
		this.pluginVersion = pluginVersion;
		this.idsVersion = idsVersion;
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
		startEpochMs = clock.millis();
		partnerName = start.getOtherPlayers().isEmpty() ? null : start.getOtherPlayers().get(0);
		capture = captureMode.getAsBoolean();
		events.add(new EntryChosen(0, entryChoice));
		events.add(new FightStarted(0, start.getSelfName(), List.copyOf(start.getOtherPlayers()), start.getSelfPosition()));
		snapshots.take(0, SnapshotKind.START).ifPresent(events::add);
	}

	/** Another player loaded into the arena after the fight began. The first one becomes the partner. */
	public void partnerSeen(String name)
	{
		if (state == SessionState.FIGHTING && partnerName == null)
		{
			partnerName = name;
			events.add(new PartnerSeen(tick, name));
		}
	}

	public void record(DomainEvent event)
	{
		if (state == SessionState.FIGHTING)
		{
			events.add(event);
		}
	}

	public void endTick()
	{
		if (state == SessionState.FIGHTING)
		{
			tick++;
		}
	}

	public void yamaDied()
	{
		end(EndReason.YAMA_DIED);
	}

	public void playerDied()
	{
		end(EndReason.PLAYER_DIED);
	}

	/** Logout, world hop, leaving the region or plugin shutdown. */
	public void leave()
	{
		end(EndReason.LEFT);
		state = SessionState.IDLE;
		entryChoice = EntryChoice.UNKNOWN;
	}

	private void end(EndReason reason)
	{
		if (state != SessionState.FIGHTING)
		{
			return;
		}
		snapshots.take(tick, SnapshotKind.END).ifPresent(events::add);
		events.add(new FightEnded(tick, reason));
		KillHeader header = new KillHeader(killIds.get(), startEpochMs, clock.millis(), pluginVersion,
			KillLog.SCHEMA_VERSION, idsVersion, entryChoice, partnerName, capture);
		KillLog kill = KillLog.of(header, events, 0);
		events.clear();
		partnerName = null;
		state = SessionState.ARMED;
		listener.killEnded(kill);
	}
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests 'com.yamareviewer.application.command.KillSessionTest'`
Expected: PASS (10 tests).

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
- Test: `src/test/java/com/yamareviewer/adapter/persistence/InMemoryFileStore.java`, `EventCodecTest.java`, `GsonLogRepositoryTest.java`

**Interfaces:**
- Consumes: Task 2 events, `KillLog`, `KillHeader`.
- Produces: `LogRepository { void save(KillLog); void saveCapture(KillLog); List<KillLog> loadAll(); void prune(int keep); }` (all `throws IOException`, `loadAll` newest first); `FileStore` (relative `/`-separated paths); `EventCodec(Gson)` with `encode`, `decode`, `encodeHeader`, `decodeHeader`, `knownTypes()`; `GsonLogRepository(FileStore, EventCodec)`, package-private `fileName(KillHeader)`, `gzip(byte[])`.

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

`src/test/java/com/yamareviewer/adapter/persistence/EventCodecTest.java`:

```java
package com.yamareviewer.adapter.persistence;

import com.google.gson.Gson;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.EntryChoice;
import com.yamareviewer.domain.event.EntryChosen;
import com.yamareviewer.domain.event.FightEnded;
import com.yamareviewer.domain.event.FightStarted;
import com.yamareviewer.domain.event.GraphicObserved;
import com.yamareviewer.domain.event.GroundGraphicObserved;
import com.yamareviewer.domain.event.HitsplatKind;
import com.yamareviewer.domain.event.HitsplatObserved;
import com.yamareviewer.domain.event.InventoryDelta;
import com.yamareviewer.domain.event.ItemStack;
import com.yamareviewer.domain.event.NpcDespawnObserved;
import com.yamareviewer.domain.event.NpcSpawnObserved;
import com.yamareviewer.domain.event.ObjectSpawnObserved;
import com.yamareviewer.domain.event.OverheadTextObserved;
import com.yamareviewer.domain.event.PartnerSeen;
import com.yamareviewer.domain.event.Position;
import com.yamareviewer.domain.event.ProjectileObserved;
import com.yamareviewer.domain.event.ProtectionPrayer;
import com.yamareviewer.domain.event.ScriptObserved;
import com.yamareviewer.domain.event.SnapshotKind;
import com.yamareviewer.domain.event.SuppliesSnapshot;
import com.yamareviewer.domain.event.TickState;
import com.yamareviewer.domain.model.KillHeader;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class EventCodecTest
{
	private final EventCodec codec = new EventCodec(new Gson());

	static List<DomainEvent> oneOfEach()
	{
		Position here = new Position(3200, 3200, 0);
		return List.of(
			new EntryChosen(0, EntryChoice.JOIN),
			new FightStarted(0, "Me", List.of("Buddy"), here),
			new FightEnded(90, EndReason.PLAYER_DIED),
			new PartnerSeen(3, "Buddy"),
			new NpcSpawnObserved(1, Actor.flare(7), 1234, 7),
			new NpcDespawnObserved(2, Actor.YAMA, 1235, 1),
			new ObjectSpawnObserved(3, 555, here),
			new OverheadTextObserved(4, Actor.YAMA, "Enough."),
			new ScriptObserved(5, 948),
			new AnimationObserved(6, Actor.PARTNER, 42),
			new GraphicObserved(7, Actor.SELF, 43),
			new GroundGraphicObserved(8, 44, here),
			new ProjectileObserved(9, 45, Actor.SELF, 11),
			new HitsplatObserved(10, Actor.JUDGE, HitsplatKind.BLOCK, 0, 12, true),
			new TickState(11, Set.of(ProtectionPrayer.MAGIC), 80, 50, 100, 4151, Actor.SELF, here, null),
			new InventoryDelta(12, 385, "Shark", -1),
			new SuppliesSnapshot(13, SnapshotKind.END, List.of(new ItemStack(385, "Shark", 3))));
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
	public void theRoundTripCoversEveryRegisteredType()
	{
		Set<String> names = oneOfEach().stream().map(e -> e.getClass().getSimpleName()).collect(Collectors.toSet());
		assertEquals(codec.knownTypes(), names);
	}

	@Test
	public void everyDomainEventClassIsRegistered()
	{
		Set<String> events = new ClassFileImporter().importPackages("com.yamareviewer.domain.event").stream()
			.filter(c -> c.isAssignableTo(DomainEvent.class) && !c.isInterface())
			.map(JavaClass::getSimpleName)
			.collect(Collectors.toSet());
		assertEquals(events, codec.knownTypes());
	}

	@Test
	public void unknownTypeDecodesToEmpty()
	{
		assertEquals(Optional.empty(), codec.decode("{\"type\":\"FromTheFuture\",\"data\":{\"tick\":1}}"));
	}

	@Test
	public void headerRoundTrips()
	{
		KillHeader header = new KillHeader("k", 1L, 2L, "0.1.0", 1, 1, EntryChoice.TRAVEL, "Buddy", true);
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
import com.yamareviewer.domain.event.EntryChoice;
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

	private static KillLog kill(String id, long start, boolean capture)
	{
		KillHeader header = new KillHeader(id, start, start + 60_000, "0.1.0", KillLog.SCHEMA_VERSION, 1, EntryChoice.TRAVEL, null, capture);
		return KillLog.of(header, List.of(new AnimationObserved(0, Actor.YAMA, 1), new FightEnded(5, EndReason.YAMA_DIED)), 0);
	}

	private List<String> loadedIds() throws IOException
	{
		return repository.loadAll().stream().map(k -> k.getHeader().getKillId()).collect(toList());
	}

	@Test
	public void savedLogLoadsBackEqual() throws IOException
	{
		KillLog kill = kill("a", 1_000, false);

		repository.save(kill);

		assertEquals(List.of(kill), repository.loadAll());
	}

	@Test
	public void loadsNewestFirst() throws IOException
	{
		repository.save(kill("old", 1_000, false));
		repository.save(kill("new", 2_000, false));

		assertEquals(List.of("new", "old"), loadedIds());
	}

	@Test
	public void pruneKeepsTheNewest() throws IOException
	{
		repository.save(kill("1", 1_000, false));
		repository.save(kill("2", 2_000, false));
		repository.save(kill("3", 3_000, false));

		repository.prune(2);

		assertEquals(List.of("3", "2"), loadedIds());
	}

	@Test
	public void corruptFileIsRenamedAndSkipped() throws IOException
	{
		repository.save(kill("good", 2_000, false));
		files.write("raw/0000000001000-bad.jsonl.gz", new byte[]{1, 2, 3});

		assertEquals(List.of("good"), loadedIds());
		assertTrue(files.paths().contains("raw/0000000001000-bad.jsonl.gz.corrupt"));
		assertFalse(files.paths().contains("raw/0000000001000-bad.jsonl.gz"));
	}

	@Test
	public void unknownEventTypesAreCountedNotFatal() throws IOException
	{
		KillHeader header = kill("x", 1_000, false).getHeader();
		String text = codec.encodeHeader(header) + "\n"
			+ "{\"type\":\"FromTheFuture\",\"data\":{}}\n"
			+ codec.encode(new FightEnded(3, EndReason.LEFT)) + "\n";
		files.write("raw/" + GsonLogRepository.fileName(header), GsonLogRepository.gzip(text.getBytes(StandardCharsets.UTF_8)));

		KillLog loaded = repository.loadAll().get(0);

		assertEquals(1, loaded.getSkippedEvents());
		assertEquals(1, loaded.getEvents().size());
	}

	@Test
	public void captureCopyIsPlainJsonLines() throws IOException
	{
		KillLog kill = kill("cap", 1_000, true);

		repository.saveCapture(kill);

		String text = new String(files.read("capture/cap.jsonl"), StandardCharsets.UTF_8);
		assertEquals(kill.getHeader(), codec.decodeHeader(text.split("\n")[0]));
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'com.yamareviewer.adapter.persistence.*'`
Expected: FAIL — `cannot find symbol` for `FileStore`, `EventCodec`, `GsonLogRepository`.

- [ ] **Step 3: Write the port and `FileStore`**

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

	/** Uncompressed copy for development (capture mode). */
	void saveCapture(KillLog kill) throws IOException;

	/** Newest first. Unreadable files are renamed with a .corrupt suffix and skipped. */
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

/** The production FileStore, rooted at Plugin.getPluginDirectory(). Verified in game, not unit tested. */
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
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.event.EntryChosen;
import com.yamareviewer.domain.event.FightEnded;
import com.yamareviewer.domain.event.FightStarted;
import com.yamareviewer.domain.event.GraphicObserved;
import com.yamareviewer.domain.event.GroundGraphicObserved;
import com.yamareviewer.domain.event.HitsplatObserved;
import com.yamareviewer.domain.event.InventoryDelta;
import com.yamareviewer.domain.event.NpcDespawnObserved;
import com.yamareviewer.domain.event.NpcSpawnObserved;
import com.yamareviewer.domain.event.ObjectSpawnObserved;
import com.yamareviewer.domain.event.OverheadTextObserved;
import com.yamareviewer.domain.event.PartnerSeen;
import com.yamareviewer.domain.event.ProjectileObserved;
import com.yamareviewer.domain.event.ScriptObserved;
import com.yamareviewer.domain.event.SuppliesSnapshot;
import com.yamareviewer.domain.event.TickState;
import com.yamareviewer.domain.model.KillHeader;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** One JSON object per line: {"type": "<SimpleClassName>", "data": {...}}. */
public final class EventCodec
{
	private static final Map<String, Class<? extends DomainEvent>> TYPES = types(
		EntryChosen.class, FightStarted.class, FightEnded.class, PartnerSeen.class,
		NpcSpawnObserved.class, NpcDespawnObserved.class, ObjectSpawnObserved.class,
		OverheadTextObserved.class, ScriptObserved.class, AnimationObserved.class,
		GraphicObserved.class, GroundGraphicObserved.class, ProjectileObserved.class,
		HitsplatObserved.class, TickState.class, InventoryDelta.class, SuppliesSnapshot.class);

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
		wrapper.addProperty("type", event.getClass().getSimpleName());
		wrapper.add("data", gson.toJsonTree(event));
		return gson.toJson(wrapper);
	}

	/** Empty for an event type this version doesn't know (written by a newer plugin version). */
	public Optional<DomainEvent> decode(String line)
	{
		JsonObject wrapper = gson.fromJson(line, JsonObject.class);
		Class<? extends DomainEvent> type = TYPES.get(wrapper.get("type").getAsString());
		if (type == null)
		{
			return Optional.empty();
		}
		return Optional.of(gson.fromJson(wrapper.get("data"), type));
	}

	public Set<String> knownTypes()
	{
		return TYPES.keySet();
	}

	@SafeVarargs
	private static Map<String, Class<? extends DomainEvent>> types(Class<? extends DomainEvent>... classes)
	{
		Map<String, Class<? extends DomainEvent>> map = new HashMap<>();
		for (Class<? extends DomainEvent> type : classes)
		{
			map.put(type.getSimpleName(), type);
		}
		return Map.copyOf(map);
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
	private static final String CAPTURE = "capture";
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
	public void saveCapture(KillLog kill) throws IOException
	{
		files.write(CAPTURE + "/" + kill.getHeader().getKillId() + ".jsonl", lines(kill).getBytes(StandardCharsets.UTF_8));
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
				kills.add(parse(new String(gunzip(files.read(path)), StandardCharsets.UTF_8)));
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

	private KillLog parse(String text)
	{
		String[] lines = text.split("\n");
		KillHeader header = codec.decodeHeader(lines[0]);
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
		return KillLog.of(header, events, skipped);
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
Expected: PASS (16 tests, including Task 3's loader tests).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/yamareviewer/application/port/LogRepository.java src/main/java/com/yamareviewer/adapter/persistence src/test/java/com/yamareviewer/adapter/persistence
git commit -m "feat: store raw kill logs as gzipped JSON lines"
```

---

### Task 6: KillEndedHandler

**Files:**
- Create: `src/main/java/com/yamareviewer/application/handler/KillEndedHandler.java`
- Test: `src/test/java/com/yamareviewer/application/handler/KillEndedHandlerTest.java`

**Interfaces:**
- Consumes: `KillEndedListener` (Task 4), `LogRepository` (Task 5).
- Produces: `KillEndedHandler(Executor executor, LogRepository logs, IntSupplier rawLogsKept)` implementing `KillEndedListener`; package-private `handle(KillLog)`. Part 2 extends `handle` with review building.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/yamareviewer/application/handler/KillEndedHandlerTest.java`:

```java
package com.yamareviewer.application.handler;

import com.yamareviewer.application.port.LogRepository;
import com.yamareviewer.domain.event.EntryChoice;
import com.yamareviewer.domain.model.KillHeader;
import com.yamareviewer.domain.model.KillLog;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class KillEndedHandlerTest
{
	private final FakeLogs logs = new FakeLogs();

	private static KillLog kill(boolean capture)
	{
		return KillLog.of(new KillHeader("k", 1L, 2L, "0.1.0", KillLog.SCHEMA_VERSION, 1, EntryChoice.UNKNOWN, null, capture), List.of(), 0);
	}

	@Test
	public void savesThenPrunes()
	{
		new KillEndedHandler(Runnable::run, logs, () -> 20).killEnded(kill(false));

		assertEquals(List.of("save k", "prune 20"), logs.calls);
	}

	@Test
	public void savesACaptureCopyInCaptureMode()
	{
		new KillEndedHandler(Runnable::run, logs, () -> 20).killEnded(kill(true));

		assertEquals(List.of("save k", "capture k", "prune 20"), logs.calls);
	}

	@Test
	public void storageFailureDoesNotEscape()
	{
		logs.fail = true;

		new KillEndedHandler(Runnable::run, logs, () -> 20).killEnded(kill(false));

		assertTrue(logs.calls.isEmpty());
	}

	@Test
	public void workRunsOnTheExecutorNotTheCaller()
	{
		List<Runnable> queued = new ArrayList<>();
		new KillEndedHandler(queued::add, logs, () -> 20).killEnded(kill(false));

		assertTrue(logs.calls.isEmpty());
		queued.forEach(Runnable::run);
		assertEquals(List.of("save k", "prune 20"), logs.calls);
	}

	private static final class FakeLogs implements LogRepository
	{
		private final List<String> calls = new ArrayList<>();
		private boolean fail;

		@Override
		public void save(KillLog kill) throws IOException
		{
			if (fail)
			{
				throw new IOException("disk full");
			}
			calls.add("save " + kill.getHeader().getKillId());
		}

		@Override
		public void saveCapture(KillLog kill)
		{
			calls.add("capture " + kill.getHeader().getKillId());
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
import java.util.concurrent.Executor;
import java.util.function.IntSupplier;
import lombok.extern.slf4j.Slf4j;

/** Everything that happens after a kill, off the client thread. Part 1: persist the raw log. */
@Slf4j
public final class KillEndedHandler implements KillEndedListener
{
	private final Executor executor;
	private final LogRepository logs;
	private final IntSupplier rawLogsKept;

	public KillEndedHandler(Executor executor, LogRepository logs, IntSupplier rawLogsKept)
	{
		this.executor = executor;
		this.logs = logs;
		this.rawLogsKept = rawLogsKept;
	}

	@Override
	public void killEnded(KillLog kill)
	{
		executor.execute(() -> handle(kill));
	}

	void handle(KillLog kill)
	{
		try
		{
			logs.save(kill);
			if (kill.getHeader().isCapture())
			{
				logs.saveCapture(kill);
			}
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
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yamareviewer/application/handler src/test/java/com/yamareviewer/application/handler
git commit -m "feat: save raw logs off the client thread when a kill ends"
```

---

### Task 7: Actor resolution and event translation

**Files:**
- Create: `src/main/java/com/yamareviewer/adapter/recording/ActorResolver.java`, `EventTranslator.java`
- Test: `src/test/java/com/yamareviewer/adapter/recording/ActorResolverTest.java`, `EventTranslatorTest.java`

**Interfaces:**
- Consumes: Task 2 events, Task 3 `IdRegistry`/`Role`/`RoleKind`.
- Produces: `ActorResolver(Client, IdRegistry, Supplier<Optional<String>> partnerName)` with `Actor resolve(net.runelite.api.Actor)`; `EventTranslator(IdRegistry, ActorResolver, IntSupplier tick, BooleanSupplier capture, IntFunction<String> itemNames, Function<LocalPoint, Position> positions)` with `reset(Map<Integer,Integer>)`, `animationChanged`, `graphicChanged`, `graphicsObjectCreated`, `projectileMoved`, `hitsplatApplied`, `overheadTextChanged`, `scriptPreFired`, `npcSpawned(NPC)`, `npcDespawned(NPC)`, `gameObjectSpawned`, `itemContainerChanged` (all returning `List<DomainEvent>`), static `aggregate(Item[])` and `entryChoice(String)`.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/yamareviewer/adapter/recording/ActorResolverTest.java`:

```java
package com.yamareviewer.adapter.recording;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.TimingRules;
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
	private final IdRegistry ids = new IdRegistry(1,
		Map.of(Role.YAMA, Set.of(100), Role.JUDGE, Set.of(101), Role.VOID_FLARE, Set.of(102), Role.METEOR_NPC, Set.of(103)),
		Map.of(), TimingRules.DEFAULT);
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
import com.yamareviewer.domain.event.GraphicObserved;
import com.yamareviewer.domain.event.HitsplatKind;
import com.yamareviewer.domain.event.HitsplatObserved;
import com.yamareviewer.domain.event.InventoryDelta;
import com.yamareviewer.domain.event.Position;
import com.yamareviewer.domain.event.ProjectileObserved;
import com.yamareviewer.domain.event.ScriptObserved;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.TimingRules;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.runelite.api.ActorSpotAnim;
import net.runelite.api.Hitsplat;
import net.runelite.api.HitsplatID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.IterableHashTable;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Projectile;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.gameval.InventoryID;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class EventTranslatorTest
{
	private final IdRegistry ids = new IdRegistry(1,
		Map.of(Role.YAMA, Set.of(100), Role.PHASE_TRANSITION_SCRIPT, Set.of(948)), Map.of(), TimingRules.DEFAULT);
	private final ActorResolver actors = mock(ActorResolver.class);
	private final NPC yama = mock(NPC.class);
	private final Player stranger = mock(Player.class);
	private boolean capture;
	private EventTranslator translator;

	@Before
	public void setUp()
	{
		when(actors.resolve(yama)).thenReturn(Actor.YAMA);
		when(actors.resolve(stranger)).thenReturn(Actor.other("Stranger"));
		translator = new EventTranslator(ids, actors, () -> 12, () -> capture, id -> "Item " + id, local -> new Position(1, 2, 0));
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
	public void onlyKnownScriptsAreRecorded()
	{
		assertEquals(List.of(new ScriptObserved(12, 948)), translator.scriptPreFired(new ScriptPreFired(948)));
		assertTrue(translator.scriptPreFired(new ScriptPreFired(1)).isEmpty());
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
	public void inventoryChangesBecomeDeltasAgainstTheBaseline()
	{
		translator.reset(Map.of(385, 5));
		ItemContainer inventory = mock(ItemContainer.class);
		when(inventory.getItems()).thenReturn(new Item[]{new Item(385, 1), new Item(385, 1), new Item(385, 1), new Item(-1, 0), new Item(3024, 1)});
		ItemContainerChanged event = new ItemContainerChanged(InventoryID.INV, inventory);

		List<DomainEvent> deltas = translator.itemContainerChanged(event);

		assertEquals(List.of(new InventoryDelta(12, 385, "Item 385", -2), new InventoryDelta(12, 3024, "Item 3024", 1)), deltas);
		assertTrue(translator.itemContainerChanged(event).isEmpty());
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
Expected: FAIL — `cannot find symbol` for `ActorResolver`, `EventTranslator`.

- [ ] **Step 3: Write `ActorResolver`**

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

/** Maps RuneLite actors to domain actors using only IDs that ship with the plugin. */
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
import com.yamareviewer.domain.event.GraphicObserved;
import com.yamareviewer.domain.event.GroundGraphicObserved;
import com.yamareviewer.domain.event.HitsplatKind;
import com.yamareviewer.domain.event.HitsplatObserved;
import com.yamareviewer.domain.event.InventoryDelta;
import com.yamareviewer.domain.event.NpcDespawnObserved;
import com.yamareviewer.domain.event.NpcSpawnObserved;
import com.yamareviewer.domain.event.ObjectSpawnObserved;
import com.yamareviewer.domain.event.OverheadTextObserved;
import com.yamareviewer.domain.event.Position;
import com.yamareviewer.domain.event.ProjectileObserved;
import com.yamareviewer.domain.event.ScriptObserved;
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
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import net.runelite.api.ActorSpotAnim;
import net.runelite.api.GameObject;
import net.runelite.api.GraphicsObject;
import net.runelite.api.Hitsplat;
import net.runelite.api.HitsplatID;
import net.runelite.api.Item;
import net.runelite.api.NPC;
import net.runelite.api.Projectile;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.GraphicsObjectCreated;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.OverheadTextChanged;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.util.Text;

/**
 * The anti-corruption layer: turns RuneLite events into raw domain observations.
 * It never classifies anything. Events about unrelated actors are dropped unless capture mode is on.
 */
public final class EventTranslator
{
	private static final int CLIENT_CYCLES_PER_TICK = 30;

	private final IdRegistry ids;
	private final ActorResolver actors;
	private final IntSupplier tick;
	private final BooleanSupplier capture;
	private final IntFunction<String> itemNames;
	private final Function<LocalPoint, Position> positions;

	private final Set<String> seenSpotAnims = new HashSet<>();
	private final Set<Projectile> seenProjectiles = Collections.newSetFromMap(new IdentityHashMap<>());
	private Map<Integer, Integer> inventory = Map.of();

	public EventTranslator(IdRegistry ids, ActorResolver actors, IntSupplier tick, BooleanSupplier capture,
		IntFunction<String> itemNames, Function<LocalPoint, Position> positions)
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

	/** Ground graphics in the arena all belong to the fight, so every one is kept. */
	public List<DomainEvent> graphicsObjectCreated(GraphicsObjectCreated event)
	{
		GraphicsObject graphic = event.getGraphicsObject();
		return List.of(new GroundGraphicObserved(tick.getAsInt(), graphic.getId(), positions.apply(graphic.getLocation())));
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

	/** ScriptPreFired fires constantly for interface scripts, so only scripts with a role are kept. */
	public List<DomainEvent> scriptPreFired(ScriptPreFired event)
	{
		return ids.roleOf(RoleKind.SCRIPT, event.getScriptId()).isPresent()
			? List.of(new ScriptObserved(tick.getAsInt(), event.getScriptId()))
			: List.of();
	}

	public List<DomainEvent> npcSpawned(NPC npc)
	{
		Actor actor = actors.resolve(npc);
		return keep(actor) ? List.of(new NpcSpawnObserved(tick.getAsInt(), actor, npc.getId(), npc.getIndex())) : List.of();
	}

	public List<DomainEvent> npcDespawned(NPC npc)
	{
		Actor actor = actors.resolve(npc);
		return keep(actor) ? List.of(new NpcDespawnObserved(tick.getAsInt(), actor, npc.getId(), npc.getIndex())) : List.of();
	}

	/** Objects spawning mid-fight are fight mechanics (glyphs), so every one is kept. */
	public List<DomainEvent> gameObjectSpawned(GameObjectSpawned event)
	{
		GameObject object = event.getGameObject();
		return List.of(new ObjectSpawnObserved(tick.getAsInt(), object.getId(), positions.apply(object.getLocalLocation())));
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

	private boolean keep(Actor actor)
	{
		return capture.getAsBoolean() || actor.getKind() != ActorKind.OTHER;
	}
}
```

Note: `ActorResolver` is not `final` so Mockito can mock it; `EventTranslator` is `final`.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.yamareviewer.adapter.recording.*'`
Expected: PASS (12 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/yamareviewer/adapter/recording src/test/java/com/yamareviewer/adapter/recording
git commit -m "feat: translate RuneLite events into raw domain observations"
```

---

### Task 8: Tick sampling, supply snapshots and plugin wiring

**Files:**
- Create: `src/main/java/com/yamareviewer/adapter/recording/TickSampler.java`, `SnapshotReader.java`
- Modify: `src/main/java/com/yamareviewer/YamaReviewerPlugin.java` (replace the Task 1 skeleton)
- Test: `src/test/java/com/yamareviewer/adapter/recording/SnapshotReaderTest.java`

**Interfaces:**
- Consumes: everything from Tasks 2–7.
- Produces: `TickSampler(Client, ActorResolver, Function<LocalPoint, Position>)` with `TickState sample(int tick, NPC yama, Player partner)`; `SnapshotReader(Client, IntFunction<String>)` implementing `SnapshotSource`; the wired plugin.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/yamareviewer/adapter/recording/SnapshotReaderTest.java`:

```java
package com.yamareviewer.adapter.recording;

import com.yamareviewer.domain.event.ItemStack;
import com.yamareviewer.domain.event.SnapshotKind;
import com.yamareviewer.domain.event.SuppliesSnapshot;
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
	private final SnapshotReader reader = new SnapshotReader(client, id -> "Item " + id);

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
		when(client.getVarbitValue(VarbitID.RUNE_POUCH_TYPE_1)).thenReturn(1);
		when(client.getVarbitValue(VarbitID.RUNE_POUCH_QUANTITY_1)).thenReturn(1000);
	}

	@Test
	public void aggregatesInventoryAndRunePouch()
	{
		Optional<SuppliesSnapshot> snapshot = reader.take(4, SnapshotKind.START);

		assertEquals(Optional.of(new SuppliesSnapshot(4, SnapshotKind.START, List.of(
			new ItemStack(385, "Item 385", 2),
			new ItemStack(556, "Item 556", 1010)))), snapshot);
	}

	@Test
	public void notLoggedInGivesNoSnapshot()
	{
		when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);

		assertEquals(Optional.empty(), reader.take(4, SnapshotKind.END));
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'com.yamareviewer.adapter.recording.SnapshotReaderTest'`
Expected: FAIL — `cannot find symbol: class SnapshotReader`.

- [ ] **Step 3: Write `SnapshotReader` and `TickSampler`**

`src/main/java/com/yamareviewer/adapter/recording/SnapshotReader.java`:

```java
package com.yamareviewer.adapter.recording;

import com.yamareviewer.application.port.SnapshotSource;
import com.yamareviewer.domain.event.ItemStack;
import com.yamareviewer.domain.event.SnapshotKind;
import com.yamareviewer.domain.event.SuppliesSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.IntFunction;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.GameState;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;

/** Inventory plus rune pouch. Client thread only. */
public final class SnapshotReader implements SnapshotSource
{
	private static final int[] RUNE_TYPES = {
		VarbitID.RUNE_POUCH_TYPE_1, VarbitID.RUNE_POUCH_TYPE_2, VarbitID.RUNE_POUCH_TYPE_3, VarbitID.RUNE_POUCH_TYPE_4
	};
	private static final int[] RUNE_QUANTITIES = {
		VarbitID.RUNE_POUCH_QUANTITY_1, VarbitID.RUNE_POUCH_QUANTITY_2, VarbitID.RUNE_POUCH_QUANTITY_3, VarbitID.RUNE_POUCH_QUANTITY_4
	};

	private final Client client;
	private final IntFunction<String> itemNames;

	public SnapshotReader(Client client, IntFunction<String> itemNames)
	{
		this.client = client;
		this.itemNames = itemNames;
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
		List<ItemStack> items = new ArrayList<>();
		quantities.forEach((itemId, quantity) -> items.add(new ItemStack(itemId, itemNames.apply(itemId), quantity)));
		return Optional.of(new SuppliesSnapshot(tick, kind, List.copyOf(items)));
	}
}
```

`src/main/java/com/yamareviewer/adapter/recording/TickSampler.java`:

```java
package com.yamareviewer.adapter.recording;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.Position;
import com.yamareviewer.domain.event.ProtectionPrayer;
import com.yamareviewer.domain.event.TickState;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Function;
import javax.annotation.Nullable;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Prayer;
import net.runelite.api.Skill;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;

/** Reads the end-of-tick state. Client thread only; verified in game. */
public final class TickSampler
{
	private final Client client;
	private final ActorResolver actors;
	private final Function<LocalPoint, Position> positions;

	public TickSampler(Client client, ActorResolver actors, Function<LocalPoint, Position> positions)
	{
		this.client = client;
		this.actors = actors;
		this.positions = positions;
	}

	public TickState sample(int tick, @Nullable NPC yama, @Nullable Player partner)
	{
		Set<ProtectionPrayer> prayers = EnumSet.noneOf(ProtectionPrayer.class);
		if (client.isPrayerActive(Prayer.PROTECT_FROM_MAGIC))
		{
			prayers.add(ProtectionPrayer.MAGIC);
		}
		if (client.isPrayerActive(Prayer.PROTECT_FROM_MISSILES))
		{
			prayers.add(ProtectionPrayer.MISSILES);
		}
		if (client.isPrayerActive(Prayer.PROTECT_FROM_MELEE))
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
			weaponId(),
			yamaTarget,
			self == null ? null : positions.apply(self.getLocalLocation()),
			partner == null ? null : positions.apply(partner.getLocalLocation()));
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

- [ ] **Step 4: Run the snapshot test to verify it passes**

Run: `./gradlew test --tests 'com.yamareviewer.adapter.recording.SnapshotReaderTest'`
Expected: PASS (2 tests).

- [ ] **Step 5: Wire the plugin**

Replace `src/main/java/com/yamareviewer/YamaReviewerPlugin.java`:

```java
package com.yamareviewer;

import com.google.gson.Gson;
import com.google.inject.Provides;
import com.yamareviewer.adapter.persistence.EventCodec;
import com.yamareviewer.adapter.persistence.FilepathFileStore;
import com.yamareviewer.adapter.persistence.GsonLogRepository;
import com.yamareviewer.adapter.persistence.IdsJsonLoader;
import com.yamareviewer.adapter.recording.ActorResolver;
import com.yamareviewer.adapter.recording.EventTranslator;
import com.yamareviewer.adapter.recording.SnapshotReader;
import com.yamareviewer.adapter.recording.TickSampler;
import com.yamareviewer.application.command.FightStart;
import com.yamareviewer.application.command.KillSession;
import com.yamareviewer.application.handler.KillEndedHandler;
import com.yamareviewer.application.port.LogRepository;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.event.Position;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.GraphicsObjectCreated;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.OverheadTextChanged;
import net.runelite.api.events.PlayerDespawned;
import net.runelite.api.events.PlayerSpawned;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Text;

/**
 * Composition root: builds the object graph and forwards RuneLite events. No logic lives here.
 * All @Subscribe methods run on the client thread.
 */
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
	private YamaReviewerConfig config;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private Gson gson;

	@Inject
	private ItemManager itemManager;

	private final Map<String, Player> players = new HashMap<>();
	private IdRegistry ids;
	private KillSession session;
	private EventTranslator translator;
	private TickSampler sampler;
	private NPC yama;

	@Provides
	YamaReviewerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(YamaReviewerConfig.class);
	}

	@Override
	protected void startUp() throws Exception
	{
		ids = new IdsJsonLoader(gson).loadBundled();
		LogRepository logs = new GsonLogRepository(new FilepathFileStore(getPluginDirectory()), new EventCodec(gson));
		IntFunction<String> itemNames = itemId -> itemManager.getItemComposition(itemId).getName();
		KillEndedHandler handler = new KillEndedHandler(executor, logs, config::rawLogsKept);
		session = new KillSession(handler, new SnapshotReader(client, itemNames), Clock.systemUTC(),
			() -> UUID.randomUUID().toString(), VERSION, ids.version(), config::captureMode);
		ActorResolver actors = new ActorResolver(client, ids, () -> session.partnerName());
		translator = new EventTranslator(ids, actors, () -> session.currentTick(), config::captureMode, itemNames, this::toPosition);
		sampler = new TickSampler(client, actors, this::toPosition);
		log.debug("Yama Reviewer started");
	}

	@Override
	protected void shutDown()
	{
		KillSession ending = session;
		clientThread.invoke(ending::leave);
		players.clear();
		yama = null;
		log.debug("Yama Reviewer stopped");
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		session.updateRegion(inYamasDomain());
		if (session.isFighting())
		{
			session.record(sampler.sample(session.currentTick(), yama, partner()));
			session.endTick();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
		{
			session.leave();
			players.clear();
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
		record(translator.npcDespawned(npc));
		if (npc == yama)
		{
			yama = null;
		}
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
		session.partnerSeen(name);
	}

	@Subscribe
	public void onPlayerDespawned(PlayerDespawned event)
	{
		Player player = event.getPlayer();
		if (player.getName() != null)
		{
			players.remove(Text.sanitize(player.getName()), player);
		}
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
	public void onScriptPreFired(ScriptPreFired event)
	{
		if (session.isFighting())
		{
			record(translator.scriptPreFired(event));
		}
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		if (session.isFighting())
		{
			record(translator.gameObjectSpawned(event));
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (session.isFighting())
		{
			record(translator.itemContainerChanged(event));
		}
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
		List<String> others = players.keySet().stream().sorted().collect(Collectors.toList());
		session.yamaSpawned(new FightStart(selfName, others, toPosition(self.getLocalLocation())));
		if (session.isFighting())
		{
			translator.reset(currentInventory());
		}
	}

	private void record(List<DomainEvent> events)
	{
		events.forEach(session::record);
	}

	private boolean inYamasDomain()
	{
		Player self = client.getLocalPlayer();
		if (self == null)
		{
			return false;
		}
		WorldPoint world = WorldPoint.fromLocalInstance(client, self.getLocalLocation());
		return world != null && ids.is(Role.YAMAS_DOMAIN, world.getRegionID());
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

	@Nullable
	private Position toPosition(@Nullable LocalPoint local)
	{
		if (local == null)
		{
			return null;
		}
		WorldPoint world = WorldPoint.fromLocalInstance(client, local);
		return world == null ? null : new Position(world.getX(), world.getY(), world.getPlane());
	}
}
```

- [ ] **Step 6: Run the whole test suite**

Run: `./gradlew test`
Expected: PASS (all tests so far).

- [ ] **Step 7: Check in game (the user runs this; never automate game input)**

Run: `./gradlew run`, log in following https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts, enable **Yama Reviewer**, and turn on **Capture mode** under Development.

Check:
1. Do one solo kill. Nothing appears on screen, in chat or as sound during or after it.
2. A file appears in `~/.runelite/plugin-data/yama-reviewer/raw/` and one in `capture/`.
3. `capture/<id>.jsonl` starts with a header line (`"entryChoice":"TRAVEL"`) and contains `TickState` lines about 0.6 s apart in tick numbers, `HitsplatObserved` lines and a final `FightEnded` with `YAMA_DIED`.
4. Teleport out mid-fight in a second attempt: a raw log with `"reason":"LEFT"` appears.
5. Log out mid-fight in a third attempt: a raw log with `LEFT` appears and it has no END `SuppliesSnapshot`.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/yamareviewer src/test/java/com/yamareviewer/adapter/recording/SnapshotReaderTest.java
git commit -m "feat: record Yama fights in game and save them when the kill ends"
```

---

### Task 9: Architecture rules

**Files:**
- Test: `src/test/java/com/yamareviewer/ArchitectureTest.java`

**Interfaces:**
- Consumes: the package layout of Tasks 1–8.
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
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import org.junit.runner.RunWith;

@RunWith(ArchUnitRunner.class)
@AnalyzeClasses(packages = "com.yamareviewer", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureTest
{
	@ArchTest
	public static final ArchRule domainDependsOnNothingOutside = noClasses()
		.that().resideInAPackage("com.yamareviewer.domain..")
		.should().dependOnClassesThat().resideInAnyPackage(
			"com.yamareviewer.application..", "com.yamareviewer.adapter..",
			"net.runelite..", "com.google.gson..", "javax.swing..");

	@ArchTest
	public static final ArchRule applicationDoesNotKnowAdapters = noClasses()
		.that().resideInAPackage("com.yamareviewer.application..")
		.should().dependOnClassesThat().resideInAnyPackage(
			"com.yamareviewer.adapter..", "net.runelite..", "com.google.gson..", "javax.swing..");

	@ArchTest
	public static final ArchRule recordingNeverReachesOutput = noClasses()
		.that().resideInAnyPackage(
			"com.yamareviewer.adapter.recording..", "com.yamareviewer.application.command..", "com.yamareviewer.domain.event..")
		.should().dependOnClassesThat().resideInAnyPackage(
			"com.yamareviewer.adapter.publish..", "com.yamareviewer.adapter.ui..");

	@ArchTest
	public static final ArchRule noLiveCues = noClasses()
		.should().dependOnClassesThat().haveFullyQualifiedName("net.runelite.client.ui.overlay.Overlay")
		.orShould().dependOnClassesThat().haveFullyQualifiedName("net.runelite.client.ui.overlay.infobox.InfoBox")
		.orShould().dependOnClassesThat().haveFullyQualifiedName("net.runelite.client.Notifier")
		.orShould().dependOnClassesThat().haveFullyQualifiedName("net.runelite.api.SoundEffectID")
		.orShould().dependOnClassesThat().resideInAPackage("javax.sound..");

	@ArchTest
	public static final ArchRule noUncheckedFileAccess = noClasses()
		.should().dependOnClassesThat().haveFullyQualifiedName("net.runelite.client.util.Filepath$Unchecked")
		.orShould().dependOnClassesThat().haveFullyQualifiedName("java.io.File")
		.orShould().dependOnClassesThat().haveFullyQualifiedName("java.nio.file.Files");
}
```

- [ ] **Step 2: Run the rules**

Run: `./gradlew test --tests 'com.yamareviewer.ArchitectureTest'`
Expected: PASS (5 rules). If a rule fails, fix the offending dependency in main code; never weaken the rule.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/yamareviewer/ArchitectureTest.java
git commit -m "test: enforce layering and the silent-during-the-fight rule"
```

---

### Task 10: Capture summary tool and the logging-kills guide

**Files:**
- Create: `src/test/java/com/yamareviewer/tools/CaptureSummary.java`
- Test: `src/test/java/com/yamareviewer/tools/CaptureSummaryTest.java`
- Modify: `build.gradle` (add the `captureSummary` task)
- Create: `docs/logging-kills.md`

**Interfaces:**
- Consumes: `EventCodec` (Task 5), events (Task 2).
- Produces: `CaptureSummary.summarize(List<DomainEvent>) → String`; `./gradlew captureSummary --args="<file>"`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/yamareviewer/tools/CaptureSummaryTest.java`:

```java
package com.yamareviewer.tools;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.GraphicObserved;
import java.util.List;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class CaptureSummaryTest
{
	@Test
	public void countsIdsPerActorWithTheirMostCommonGap()
	{
		String summary = CaptureSummary.summarize(List.of(
			new AnimationObserved(0, Actor.YAMA, 42),
			new AnimationObserved(7, Actor.YAMA, 42),
			new AnimationObserved(14, Actor.YAMA, 42),
			new GraphicObserved(5, Actor.SELF, 3247)));

		assertTrue(summary, summary.contains(String.format("%-40s %6d %6d %6d %6d", "animation YAMA 42", 3, 0, 14, 7)));
		assertTrue(summary, summary.contains(String.format("%-40s %6d %6d %6d %6s", "graphic SELF 3247", 1, 5, 5, "-")));
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'com.yamareviewer.tools.CaptureSummaryTest'`
Expected: FAIL — `cannot find symbol: class CaptureSummary`.

- [ ] **Step 3: Write `CaptureSummary`**

`src/test/java/com/yamareviewer/tools/CaptureSummary.java`:

```java
package com.yamareviewer.tools;

import com.google.gson.Gson;
import com.yamareviewer.adapter.persistence.EventCodec;
import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.event.GraphicObserved;
import com.yamareviewer.domain.event.GroundGraphicObserved;
import com.yamareviewer.domain.event.HitsplatObserved;
import com.yamareviewer.domain.event.NpcSpawnObserved;
import com.yamareviewer.domain.event.ObjectSpawnObserved;
import com.yamareviewer.domain.event.OverheadTextObserved;
import com.yamareviewer.domain.event.ProjectileObserved;
import com.yamareviewer.domain.event.ScriptObserved;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Development tool: which IDs appeared in a capture file, how often, and at what rhythm. */
public final class CaptureSummary
{
	public static void main(String[] args) throws IOException
	{
		if (args.length != 1)
		{
			System.err.println("Usage: ./gradlew captureSummary --args=\"<path to capture .jsonl>\"");
			System.exit(1);
		}
		EventCodec codec = new EventCodec(new Gson());
		List<String> lines = Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8);
		List<DomainEvent> events = new ArrayList<>();
		for (String line : lines.subList(1, lines.size()))
		{
			if (!line.isBlank())
			{
				codec.decode(line).ifPresent(events::add);
			}
		}
		System.out.print(summarize(events));
	}

	static String summarize(List<DomainEvent> events)
	{
		Map<String, List<Integer>> ticksByKey = new TreeMap<>();
		for (DomainEvent event : events)
		{
			String key = keyOf(event);
			if (key != null)
			{
				ticksByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(event.getTick());
			}
		}
		StringBuilder out = new StringBuilder(String.format("%-40s %6s %6s %6s %6s%n", "event actor id", "count", "first", "last", "gap"));
		ticksByKey.forEach((key, ticks) -> out.append(String.format("%-40s %6d %6d %6d %6s%n",
			key, ticks.size(), ticks.get(0), ticks.get(ticks.size() - 1), mostCommonGap(ticks))));
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

	private static String keyOf(DomainEvent event)
	{
		if (event instanceof AnimationObserved)
		{
			AnimationObserved e = (AnimationObserved) event;
			return "animation " + label(e.getActor()) + " " + e.getAnimationId();
		}
		if (event instanceof GraphicObserved)
		{
			GraphicObserved e = (GraphicObserved) event;
			return "graphic " + label(e.getActor()) + " " + e.getGraphicId();
		}
		if (event instanceof GroundGraphicObserved)
		{
			return "ground-graphic " + ((GroundGraphicObserved) event).getGraphicId();
		}
		if (event instanceof ProjectileObserved)
		{
			ProjectileObserved e = (ProjectileObserved) event;
			return "projectile " + e.getProjectileId() + " -> " + label(e.getTarget());
		}
		if (event instanceof NpcSpawnObserved)
		{
			return "npc " + ((NpcSpawnObserved) event).getNpcId();
		}
		if (event instanceof ObjectSpawnObserved)
		{
			return "object " + ((ObjectSpawnObserved) event).getObjectId();
		}
		if (event instanceof ScriptObserved)
		{
			return "script " + ((ScriptObserved) event).getScriptId();
		}
		if (event instanceof OverheadTextObserved)
		{
			OverheadTextObserved e = (OverheadTextObserved) event;
			return "overhead " + label(e.getActor()) + " \"" + e.getText() + "\"";
		}
		if (event instanceof HitsplatObserved)
		{
			HitsplatObserved e = (HitsplatObserved) event;
			return "hitsplat " + label(e.getTarget()) + " type " + e.getRawType();
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

Four kills with capture mode on give the IDs that Parts 3 and 4 need.
Only you can do this: never automate game input.

## Setup

1. `./gradlew run`, then log in following https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts.
2. Enable **Yama Reviewer**. Under **Development**, turn on **Capture mode**.

## The four kills

| Kill | Mode | Do deliberately, and note the rough time |
| --- | --- | --- |
| 1 | Solo (Travel) | Pray correctly all of P3. Use every spec weapon you own at least once. |
| 2 | Solo (Travel) | In P3: switch one prayer a tick late, pray the wrong style once, walk into one Shadow Crash, let one flare explode, stand next to Yama once to get meleed. |
| 3 | Duo host (Travel, partner joins) | Normal kill. Note who Yama targets in P3. |
| 4 | Duo joiner (Join) | Normal kill. |

Files land in `~/.runelite/plugin-data/yama-reviewer/capture/<killId>.jsonl`.

## Reading a capture

    ./gradlew captureSummary --args="$HOME/.runelite/plugin-data/yama-reviewer/capture/<killId>.jsonl"

Each row is one kind of event on one actor with one ID, with its count, first and last tick and its most common gap.

- `YAMA_STANDARD_ATTACK`: a `YAMA` animation with gap 7 whose count is about the P3 length divided by 7.
- `P3_MAGIC_ON_PLAYER` / `P3_RANGED_ON_PLAYER`: two `SELF` graphics that alternate, each with gap 14.
- `SHADOW_CRASH`: ground graphics or graphics that come in threes.
- `FLARE_EXPLOSION`: a graphic near a `FLARE(n)` despawn, followed by a heal hitsplat on `YAMA`.
- Spec animations: `SELF` animations on the tick your spec energy drops (see `TickState.specEnergy`).
- Compare every candidate with the gameval hints table in spec section 5.5.

## After the logging kills

1. Fill the matching roles in `src/main/resources/com/yamareviewer/ids.json` and raise `"version"` by 1.
2. Set `timing.prayerCheck` and `prayerCheckOffset` from kill 2 (which tick decided the late switch).
3. Copy the four capture files to `src/test/resources/fixtures/` for the golden tests in Part 3.
```

- [ ] **Step 7: Commit**

```bash
git add build.gradle src/test/java/com/yamareviewer/tools docs/logging-kills.md
git commit -m "feat: add the capture summary tool and the logging-kills guide"
```

---

## Self-Review

**Spec coverage (Part 1 of section 13):** project setup (Task 1); domain events of 5.1 (Task 2, plus `PartnerSeen`, `GroundGraphicObserved`); `KillLog`/header/storage of 5.2 (Tasks 2, 5); fight lifecycle 5.3 (Tasks 4, 8); `IdRegistry` + `ids.json` 5.5 (Task 3); capture mode (Tasks 5, 6, 8, 10); silence tests 4.4 (Task 9; the "publisher untouched until FightEnded" test moves to Part 2 where a publisher exists); threading 4.5 (Tasks 6, 8); error handling 11 for storage (Tasks 5, 6). Mode detection (5.4) and everything in sections 6–9 are Parts 2–4.

**Deviations from the spec to fold back into it:** the events `NpcSpawned`, `NpcDespawned`, `ObjectSpawned` are named `NpcSpawnObserved`, `NpcDespawnObserved`, `ObjectSpawnObserved` (RuneLite has classes with the old names); `PartnerSeen` is added; the commands are `KillSession` methods rather than command classes; ground graphics, projectiles and objects are recorded without an ID filter so self-healing can find renumbered IDs.

**Type consistency:** `KillEndedListener.killEnded(KillLog)`, `SnapshotSource.take(int, SnapshotKind)`, `LogRepository` signatures and `EventTranslator.aggregate(Item[])` are used identically in Tasks 4–8.
