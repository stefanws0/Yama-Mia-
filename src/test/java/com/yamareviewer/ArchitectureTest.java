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

	@ArchTest
	public static final ArchRule onlyUiOpensLinksAndTheClipboard = noClasses()
		.that().resideOutsideOfPackage("com.yamareviewer.adapter.ui..")
		.should().dependOnClassesThat().haveFullyQualifiedName("net.runelite.client.util.LinkBrowser")
		.orShould().dependOnClassesThat().resideInAPackage("java.awt.datatransfer..");
}
