package me.bechberger.testorder.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

import me.bechberger.testorder.ops.PluginContext;

/**
 * Unit tests for {@link TestOrderPlugin#aggregateNativeProjects(Project,
 * TestOrderExtension)}: nativeTests declarations are aggregated across all
 * projects of the build so a workflow running on a project without
 * nativeTests still detects sibling-owned native changes.
 */
class AggregateNativeProjectsTest {

    @Test
    void aggregatesEntriesFromDeclaringProjectsAcrossTheBuild() {
        Project root = ProjectBuilder.builder().withName("root").build();
        Project lib = ProjectBuilder.builder().withName("lib").withParent(root).build();
        Project app = ProjectBuilder.builder().withName("app").withParent(root).build();
        new TestOrderPlugin().apply(root);
        new TestOrderPlugin().apply(lib);
        new TestOrderPlugin().apply(app);

        TestOrderExtension libExt = lib.getExtensions().findByType(TestOrderExtension.class);
        libExt.getNativeTests().getSourceRoots().setFrom(new File(lib.getProjectDir(), "src/main/cpp"));

        List<PluginContext.NativeProjectEntry> entries =
                TestOrderPlugin.aggregateNativeProjects(root, root.getExtensions().findByType(TestOrderExtension.class));

        assertEquals(1, entries.size(), "only :lib declares nativeTests: " + entries);
        PluginContext.NativeProjectEntry entry = entries.get(0);
        assertEquals(lib.getProjectDir().toPath().toAbsolutePath().normalize(), entry.keyBase(),
                "key base must be the DECLARING project's directory");
        assertTrue(entry.roots().contains(lib.getProjectDir().toPath().resolve("src/main/cpp")
                .toAbsolutePath().normalize()));
        assertEquals(libExt.getNativeHashFile().get().getAsFile().toPath(), entry.hashFile());
    }

    @Test
    void includesTheRunningProjectWhenItDeclaresNativeTests() {
        Project root = ProjectBuilder.builder().withName("root").build();
        Project app = ProjectBuilder.builder().withName("app").withParent(root).build();
        new TestOrderPlugin().apply(root);
        new TestOrderPlugin().apply(app);

        TestOrderExtension appExt = app.getExtensions().findByType(TestOrderExtension.class);
        appExt.getNativeTests().getSourceRoots().setFrom(new File(app.getProjectDir(), "natives"));

        List<PluginContext.NativeProjectEntry> entries =
                TestOrderPlugin.aggregateNativeProjects(app, appExt);
        assertEquals(1, entries.size());
        assertEquals(app.getProjectDir().toPath().toAbsolutePath().normalize(), entries.get(0).keyBase());
    }

    @Test
    void projectsWithoutNativeTestsProduceNoEntries() {
        Project root = ProjectBuilder.builder().withName("root").build();
        Project app = ProjectBuilder.builder().withName("app").withParent(root).build();
        new TestOrderPlugin().apply(root);
        new TestOrderPlugin().apply(app);

        List<PluginContext.NativeProjectEntry> entries =
                TestOrderPlugin.aggregateNativeProjects(app, app.getExtensions().findByType(TestOrderExtension.class));
        assertTrue(entries.isEmpty());
    }
}
