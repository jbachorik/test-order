package me.bechberger.testorder.ops.workflows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.ops.PluginContext;
import me.bechberger.testorder.ops.PluginLog;

/**
 * Multi-project native change propagation: the workflow runs on a project
 * WITHOUT nativeTests (e.g. :ddprof-test) while a sibling project (e.g.
 * :ddprof-lib) owns the native sources and the native index edges. The
 * aggregated {@link PluginContext.NativeProjectEntry} list must surface the
 * sibling's changed native files — keyed owning-project-relative — in the
 * changed-unit universe, or native changes never influence Java test selection
 * on multi-project consumers.
 */
class ChangeAnalysisNativeAggregationTest {

	@TempDir
	Path tempDir;

	private void git(Path dir, String... args) throws IOException, InterruptedException {
		var cmd = new java.util.ArrayList<String>();
		cmd.add("git");
		java.util.Collections.addAll(cmd, args);
		ProcessBuilder pb = new ProcessBuilder(cmd);
		pb.directory(dir.toFile());
		pb.environment().put("GIT_CONFIG_NOSYSTEM", "1");
		pb.environment().put("GIT_TERMINAL_PROMPT", "0");
		pb.environment().put("GIT_CONFIG_COUNT", "3");
		pb.environment().put("GIT_CONFIG_KEY_0", "gc.auto");
		pb.environment().put("GIT_CONFIG_VALUE_0", "0");
		pb.environment().put("GIT_CONFIG_KEY_1", "user.email");
		pb.environment().put("GIT_CONFIG_VALUE_1", "t@example.com");
		pb.environment().put("GIT_CONFIG_KEY_2", "user.name");
		pb.environment().put("GIT_CONFIG_VALUE_2", "t");
		pb.redirectErrorStream(true);
		Process p = pb.start();
		p.getInputStream().readAllBytes();
		if (p.waitFor() != 0) {
			throw new RuntimeException("git " + String.join(" ", args) + " failed");
		}
	}

	private Path write(Path relative, String content) throws IOException {
		Files.createDirectories(relative.getParent());
		Files.writeString(relative, content, StandardCharsets.UTF_8);
		return relative;
	}

	private Path write(String relative, String content) throws IOException {
		return write(tempDir.resolve(relative), content);
	}

	private PluginContext.Builder baseContext(Path indexFile) {
		return PluginContext.builder()
				// the workflow runs on the app project, which owns no natives
				.projectRoot(tempDir.resolve("app")).repoRoot(tempDir).indexFile(indexFile)
				.stateFile(tempDir.resolve(".test-order/state.lz4")).changeMode("uncommitted")
				.staticAnalysisEnabled(false).log(PluginLog.NOOP);
	}

	@Test
	void siblingProjectNativeChangesReachTheWorkflowRunningOnAnotherProject() throws Exception {
		git(tempDir, "init", "-q");
		write("lib/src/main/cpp/api.cpp", "int api() { return 1; }");
		write("lib/src/main/cpp/extra.cpp", "int extra() { return 2; }");
		Files.createDirectories(tempDir.resolve("app"));
		git(tempDir, "add", ".");
		git(tempDir, "commit", "-q", "-m", "init");

		// index: a test in the shared index carries the native edge
		// (lib-relative key), as written by testOrderNativeCollect on :lib
		Path indexFile = tempDir.resolve(".test-order/test-dependencies.lz4");
		Files.createDirectories(indexFile.getParent());
		DependencyMap map = new DependencyMap();
		map.put("com.example.ApiTest", Set.of("com.example.Api", "src/main/cpp/api.cpp"));
		map.save(indexFile);

		// uncommitted native changes owned by :lib
		Files.writeString(tempDir.resolve("lib/src/main/cpp/api.cpp"), "int api() { return 3; }",
				StandardCharsets.UTF_8);
		write("lib/src/main/cpp/new.cpp", "int neu() { return 4; }");

		PluginContext ctx = baseContext(indexFile)
				.nativeProjects(List.of(new PluginContext.NativeProjectEntry(tempDir.resolve("lib"),
						tempDir.resolve("lib-native-hashes.lz4"), List.of(tempDir.resolve("lib/src/main/cpp")))))
				.build();

		ChangeAnalysis.Result result = ChangeAnalysis.analyze(ctx,
				new ChangeAnalysis.Options(false, false, false, false));

		// lib-relative keys — not prefixed with the sibling project directory
		assertTrue(result.changedClasses().contains("src/main/cpp/api.cpp"),
				"native change from the sibling project must join the changed-unit universe: "
						+ result.changedClasses());
		assertTrue(result.changedClasses().contains("src/main/cpp/new.cpp"),
				"untracked native file must be detected: " + result.changedClasses());
		// the app project has no Java sources — nothing else may leak in
		assertEquals(Set.of("src/main/cpp/api.cpp", "src/main/cpp/new.cpp"), result.changedClasses());
	}

	@Test
	void explicitChangedClassesStayAuthoritativeOverNativeDetection() throws Exception {
		git(tempDir, "init", "-q");
		write("lib/src/main/cpp/api.cpp", "int api() { return 1; }");
		Files.createDirectories(tempDir.resolve("app"));
		git(tempDir, "add", ".");
		git(tempDir, "commit", "-q", "-m", "init");
		Files.writeString(tempDir.resolve("lib/src/main/cpp/api.cpp"), "int api() { return 3; }",
				StandardCharsets.UTF_8);

		Path indexFile = tempDir.resolve(".test-order/test-dependencies.lz4");
		Files.createDirectories(indexFile.getParent());
		DependencyMap map = new DependencyMap();
		map.put("com.example.ApiTest", Set.of("src/main/cpp/api.cpp"));
		map.save(indexFile);

		PluginContext ctx = baseContext(indexFile).changedClasses("com.example.ExplicitlyChanged")
				.nativeProjects(List.of(new PluginContext.NativeProjectEntry(tempDir.resolve("lib"),
						tempDir.resolve("h.lz4"), List.of(tempDir.resolve("lib/src/main/cpp")))))
				.build();

		ChangeAnalysis.Result result = ChangeAnalysis.analyze(ctx,
				new ChangeAnalysis.Options(false, false, false, false));
		assertEquals(Set.of("com.example.ExplicitlyChanged"), result.changedClasses());
	}

	@Test
	void singleProjectFieldsSynthesizeOneNativeEntry() {
		// legacy shape: builder sets roots + hash file without explicit entries
		PluginContext ctx = PluginContext.builder().projectRoot(tempDir)
				.nativeRoots(List.of(tempDir.resolve("src/cpp"))).nativeHashFile(tempDir.resolve("native-hashes.lz4"))
				.build();
		assertEquals(1, ctx.nativeProjects().size());
		assertEquals(tempDir, ctx.nativeProjects().get(0).keyBase());
		assertEquals(tempDir.resolve("native-hashes.lz4"), ctx.nativeProjects().get(0).hashFile());
		assertEquals(List.of(tempDir.resolve("src/cpp")), ctx.nativeProjects().get(0).roots());

		// explicit entries win; no synthesis on top
		PluginContext aggregated = PluginContext.builder().projectRoot(tempDir)
				.nativeRoots(List.of(tempDir.resolve("src/cpp"))).nativeHashFile(tempDir.resolve("native-hashes.lz4"))
				.nativeProjects(List.of(new PluginContext.NativeProjectEntry(tempDir.resolve("other"),
						tempDir.resolve("other-h.lz4"), List.of(tempDir.resolve("other/cpp")))))
				.build();
		assertEquals(1, aggregated.nativeProjects().size());
		assertEquals(tempDir.resolve("other"), aggregated.nativeProjects().get(0).keyBase());

		// no native config at all → empty
		assertEquals(0, PluginContext.builder().projectRoot(tempDir).build().nativeProjects().size());
	}
}
