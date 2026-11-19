package me.bechberger.testorder.gradle;

import static org.gradle.testkit.runner.TaskOutcome.FAILED;
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import me.bechberger.testorder.DependencyMap;

/**
 * Integration tests for the native (C/C++) support: {@code nativeTests} DSL,
 * the {@code testOrderNativeCollect} task, and native change detection inputs.
 */
@Timeout(value = 8, unit = TimeUnit.MINUTES)
class NativeCollectIntegrationTest {

    @TempDir
    Path projectDir;

    private void scaffoldNativeProject() throws IOException {
        writeFile("settings.gradle", """
                pluginManagement {
                    repositories {
                        mavenLocal()
                        gradlePluginPortal()
                        mavenCentral()
                    }
                }
                rootProject.name = 'native-project'
                """);
        writeFile("build.gradle", """
                plugins {
                    id 'me.bechberger.test-order' version '0.1.1'
                }
                testOrder {
                    nativeTests {
                        sourceRoots = files('src/profiling')
                        coverageDir = layout.projectDirectory.dir('build/native-coverage')
                    }
                }
                """);
        // JNI-exporting native source tracked by nativeTests.sourceRoots
        writeFile("src/profiling/foo.cpp", """
                #include <jni.h>
                JNIEXPORT void JNICALL Java_com_example_Foo_run(JNIEnv* env, jobject obj) {}
                """);
        // per-test gcov artifact (generic shape)
        writeFile("build/native-coverage/com.example.BarTest.json", """
                {"test": "com.example.BarTest", "coveredFiles": ["src/profiling/foo.cpp"]}
                """);
        // pre-built dependency index: FooTest exercises Foo#run (MEMBER mode)
        DependencyMap map = new DependencyMap();
        map.put("com.example.FooTest", Set.of("com.example.Foo"));
        map.putMemberDeps("com.example.FooTest", Set.of("com.example.Foo#run"));
        map.put("com.example.BarTest", Set.of("com.example.Foo"));
        Path indexFile = projectDir.resolve(".test-order/test-dependencies.lz4");
        Files.createDirectories(indexFile.getParent());
        map.save(indexFile);
    }

    private void writeFile(String relativePath, String content) throws IOException {
        Path target = projectDir.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    private GradleRunner runner(String... args) {
        return GradleRunner.create()
                .withGradleVersion("9.9.1")
                .withProjectDir(projectDir.toFile())
                .withArguments(args)
                .forwardOutput();
    }

    @Test
    @DisplayName("testOrderNativeCollect merges gcov and JNI cross-check edges into the index")
    void nativeCollectMergesEdgesIntoIndex() throws IOException {
        scaffoldNativeProject();

        BuildResult result = runner("testOrderNativeCollect", "--quiet").build();
        assertEquals(SUCCESS, result.task(":testOrderNativeCollect").getOutcome());

        DependencyMap after = DependencyMap.load(projectDir.resolve(".test-order/test-dependencies.lz4"));
        // gcov edge for BarTest (com.example.Foo appears ubiquitously — it is a dep of
        // every indexed test — so only assert the native edge)
        assertTrue(after.get("com.example.BarTest").contains("src/profiling/foo.cpp"));
        // cross-check edge for FooTest: member dep com.example.Foo#run is exported from foo.cpp
        assertTrue(after.get("com.example.FooTest").contains("src/profiling/foo.cpp"));
        assertTrue(after.get("com.example.FooTest").contains("com.example.Foo"));
    }

    @Test
    @DisplayName("testOrderNativeCollect fails with actionable message when nativeTests is missing")
    void nativeCollectRequiresConfiguration() throws IOException {
        scaffoldNativeProject();
        // strip the nativeTests block
        writeRichBuildGradleWithoutNativeTests();

        BuildResult result = runner("testOrderNativeCollect").buildAndFail();
        assertEquals(FAILED, result.task(":testOrderNativeCollect").getOutcome());
        assertTrue(result.getOutput().contains("requires native source roots"),
                "expected actionable failure, got: " + result.getOutput());
    }

    @Test
    @DisplayName("native hash snapshot is created when nativeTests is configured")
    void nativeHashSnapshotIsWritten() throws IOException {
        scaffoldNativeProject();
        BuildResult result = runner("testOrderSnapshot", "--quiet").build();
        assertEquals(SUCCESS, result.task(":testOrderSnapshot").getOutcome());
        assertTrue(Files.exists(projectDir.resolve(".test-order/native-hashes.lz4")),
                "native hash snapshot must be written for since-last-run native change detection");
    }

    private void writeRichBuildGradleWithoutNativeTests() throws IOException {
        writeFile("build.gradle", """
                plugins {
                    id 'me.bechberger.test-order' version '0.1.1'
                }
                """);
    }

    @Test
    @DisplayName("task registration: testOrderNativeCollect exists with group and description")
    void nativeCollectTaskRegisters() {
        org.gradle.api.Project project = org.gradle.testfixtures.ProjectBuilder.builder().build();
        new TestOrderPlugin().apply(project);
        var task = project.getTasks().findByName("testOrderNativeCollect");
        assertNotNull(task, "testOrderNativeCollect must be registered");
        assertEquals("test-order", task.getGroup());
        assertNotNull(task.getDescription());
    }
}
