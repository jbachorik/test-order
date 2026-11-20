package me.bechberger.testorder.gradle;

import static org.gradle.testkit.runner.TaskOutcome.FAILED;
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import me.bechberger.testorder.changes.NativeChangeDetector;

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
                    id 'me.bechberger.test-order'
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
                .withPluginClasspath()
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
    @DisplayName("subproject native paths and relative coverage artifacts use the declaring project base")
    void subprojectNativeCollectionUsesDeclaringProjectRelativeKeys() throws IOException {
        writeFile("settings.gradle", """
                pluginManagement {
                    repositories {
                        mavenLocal()
                        gradlePluginPortal()
                        mavenCentral()
                    }
                }
                rootProject.name = 'multi-native-project'
                include 'native-module'
                """);
        writeFile("build.gradle", "");
        writeFile("native-module/build.gradle", """
                plugins {
                    id 'me.bechberger.test-order'
                }
                testOrder {
                    nativeTests {
                        sourceRoots = files('.', '../outside-native', 'src/profiling')
                        coverageDir = layout.projectDirectory.dir('build/native-coverage')
                    }
                }
                """);
        writeFile("native-module/src/profiling/foo.cpp", """
                #include <jni.h>
                JNIEXPORT void JNICALL Java_com_example_Native_run(JNIEnv* env, jobject obj) {}
                """);
        writeFile("outside-native/outside.cpp", """
                #include <jni.h>
                JNIEXPORT void JNICALL Java_com_example_Outside_run(JNIEnv* env, jobject obj) {}
                """);
        writeFile("native-module/build/native-coverage/com.example.CoverageTest.json", """
                {"test": "com.example.CoverageTest", "coveredFiles": ["src/profiling/foo.cpp"]}
                """);

        DependencyMap map = new DependencyMap();
        map.put("com.example.JniTest", Set.of("com.example.Native"));
        map.putMemberDeps("com.example.JniTest", Set.of("com.example.Native#run"));
        map.put("com.example.OutsideJniTest", Set.of("com.example.Outside"));
        map.putMemberDeps("com.example.OutsideJniTest", Set.of("com.example.Outside#run"));
        Path indexFile = projectDir.resolve(".test-order/test-dependencies.lz4");
        Files.createDirectories(indexFile.getParent());
        map.save(indexFile);

        BuildResult result = runner(":native-module:testOrderNativeCollect").build();
        assertEquals(SUCCESS, result.task(":native-module:testOrderNativeCollect").getOutcome());

        DependencyMap after = DependencyMap.load(indexFile);
        assertTrue(after.get("com.example.CoverageTest").contains("src/profiling/foo.cpp"),
                "relative artifact path and source root should resolve from native-module");
        assertTrue(after.get("com.example.JniTest").contains("src/profiling/foo.cpp"),
                "native unit keys should be relative to the project declaring nativeTests");
        assertTrue(after.get("com.example.JniTest").stream().noneMatch(path -> path.startsWith("native-module/")),
                "root-project-relative native keys must not leak into the index");
        assertTrue(after.get("com.example.OutsideJniTest").stream().noneMatch(path -> path.contains("outside-native")),
                "source roots outside the declaring project must not create native edges");
        assertTrue(result.getOutput().contains("outside-native"),
                "rejected source roots should be reported with a warning");
        Path moduleRoot = projectDir.resolve("native-module");
        assertTrue(result.getOutput().contains(moduleRoot.toString()),
                "a source root equal to the declaring project base should be accepted with a warning");
        assertEquals(Set.of("src/profiling/foo.cpp"), NativeChangeDetector
                .scanNativeRoots(moduleRoot, java.util.List.of(moduleRoot.resolve("src/profiling"))).keySet(),
                "native hash snapshot keys should use the same declaring-project-relative base");
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

    @Test
    @DisplayName("declared binaries scope collection to sources compiled into the tested build")
    void declaredBinariesScopeCollectionToCompiledSources() throws IOException {
        scaffoldNativeProject();
        // vendored source under the same root, with its own export and coverage
        writeFile("src/profiling/vendored.cpp", ""
                + "#include <jni.h>\n"
                + "JNIEXPORT void JNICALL Java_com_example_Vendored_run(JNIEnv* env, jobject obj) {}\n");
        writeFile("build/native-coverage/com.example.BarTest.json", ""
                + "{\"test\": \"com.example.BarTest\", \"coveredFiles\": "
                + "[\"src/profiling/foo.cpp\", \"src/profiling/vendored.cpp\"]}\n");
        // fake gcov notes: only foo.cpp was compiled into the tested build
        Path objectsDir = projectDir.resolve("build/native-objects");
        Files.createDirectories(objectsDir);
        Files.writeString(objectsDir.resolve("foo.gcno"),
                "gcno\u00ff\u00ff\u0000" + projectDir.resolve("src/profiling/foo.cpp") + "\u0000");
        writeFile("build.gradle", ""
                + "plugins {\n"
                + "    id 'me.bechberger.test-order'\n"
                + "}\n"
                + "testOrder {\n"
                + "    nativeTests {\n"
                + "        sourceRoots = files('src/profiling')\n"
                + "        coverageDir = layout.projectDirectory.dir('build/native-coverage')\n"
                + "        binaries = files('build/native-objects')\n"
                + "    }\n"
                + "}\n");

        BuildResult result = runner("testOrderNativeCollect", "--quiet").build();
        assertEquals(SUCCESS, result.task(":testOrderNativeCollect").getOutcome());

        DependencyMap after = DependencyMap.load(projectDir.resolve(".test-order/test-dependencies.lz4"));
        assertTrue(after.get("com.example.BarTest").contains("src/profiling/foo.cpp"),
                "compiled covered source must keep its edge");
        assertTrue(!after.get("com.example.BarTest").contains("src/profiling/vendored.cpp"),
                "covered source never compiled into the declared binaries must be dropped: "
                        + after.get("com.example.BarTest"));
        assertTrue(!after.get("com.example.FooTest").contains("src/profiling/vendored.cpp"),
                "cross-check must be scoped too: " + after.get("com.example.FooTest"));
    }

    private void writeRichBuildGradleWithoutNativeTests() throws IOException {
        writeFile("build.gradle", """
                plugins {
                    id 'me.bechberger.test-order'
                }
                """);
    }

    @Test
    @DisplayName("plain nativeTests wiring schedules no native build tasks for testOrderNativeCollect")
    void plainWiringAddsNoTaskDependencies() throws IOException {
        scaffoldNativeProject();

        BuildResult result = runner("testOrderNativeCollect", "--quiet").build();
        assertEquals(SUCCESS, result.task(":testOrderNativeCollect").getOutcome());
        // the only scheduled tasks are the requested one and Gradle lifecycle helpers;
        // no native build task exists in the fixture, so none may appear in the graph
        for (String taskPath : result.getTasks().stream().map(t -> t.getPath()).toList()) {
            assertTrue(taskPath.equals(":testOrderNativeCollect") || taskPath.startsWith(":help")
                    || taskPath.endsWith("TestOrderNativeCollect") && taskPath.startsWith(":")
                            && !taskPath.equals(":nativeBuild") && !taskPath.contains("compile")
                            && !taskPath.contains("link"),
                    "unexpected task in graph: " + taskPath);
        }
    }

    @Test
    @DisplayName("builtBy-carried sourceRoots do not drag producer tasks into the collect task graph")
    void builtBySourceRootsDoNotDragProducersIntoGraph() throws IOException {
        scaffoldNativeProject();
        // Mirrors a consumer wiring where the source-roots file collection carries
        // builtBy producer tasks (the only Gradle mechanism that could pull native
        // build tasks like :ddprof-test-native:compileLib/:linkLib into a collect run).
        writeFile("build.gradle", """
                plugins {
                    id 'me.bechberger.test-order'
                }
                tasks.register('makeNativeLib') {
                    doLast { } // pretend native build
                }
                testOrder {
                    nativeTests {
                        sourceRoots = files('src/profiling') { builtBy 'makeNativeLib' }
                        coverageDir = layout.projectDirectory.dir('build/native-coverage')
                    }
                }
                """);

        BuildResult result = runner("testOrderNativeCollect", "--quiet").build();
        assertEquals(SUCCESS, result.task(":testOrderNativeCollect").getOutcome());
        assertNull(result.task(":makeNativeLib"),
                "builtBy on nativeTests.sourceRoots must not drag the producer task into the "
                        + "testOrderNativeCollect graph — if this fails, the plugin has an input "
                        + "or dependsOn leak");
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
