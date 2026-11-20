package me.bechberger.testorder.nativecov;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JniExportScannerTest {

	@Test
	void demanglesPlainNames() {
		assertEquals("com.example.Foo#bar", JniExportScanner.demangle("Java_com_example_Foo_bar"));
		assertEquals("Foo#bar", JniExportScanner.demangle("Java_Foo_bar"));
	}

	@Test
	void demanglesUnderscoreEscapes() {
		// method foo_bar → foo_1bar
		assertEquals("com.example.Foo#foo_bar", JniExportScanner.demangle("Java_com_example_Foo_foo_1bar"));
		// class Native_Lib → Native_1Lib
		assertEquals("com.example.Native_Lib#run", JniExportScanner.demangle("Java_com_example_Native_1Lib_run"));
	}

	@Test
	void stripsOverloadSignatureSuffix() {
		assertEquals("com.example.Foo#bar", JniExportScanner.demangle("Java_com_example_Foo_bar__I"));
		assertEquals("com.example.Foo#bar",
				JniExportScanner.demangle("Java_com_example_Foo_bar__Ljava_lang_String_2II"));
	}

	@Test
	void rejectsNonJniOrMalformedNames() {
		assertNull(JniExportScanner.demangle("java_not_a_jni_name"));
		assertNull(JniExportScanner.demangle("Java_"));
		assertNull(JniExportScanner.demangle("Java_onlyclass"));
		assertNull(JniExportScanner.demangle("Java_Class_"));
		assertNull(JniExportScanner.demangle(null));
		assertNull(JniExportScanner.demangle("Foo_bar"));
	}

	@Test
	void scanFileFindsAllExports() {
		String source = """
				#include <jni.h>
				JNIEXPORT void JNICALL Java_com_example_Foo_start(JNIEnv* env, jobject o) {}
				static void helper() {}
				JNIEXPORT jint JNICALL Java_com_example_Foo_read__I(JNIEnv* env, jobject o, jint v) { return 0; }
				void Java_com_example_Foo_foo_1bar(JNIEnv* env) {}
				""";
		assertEquals(Set.of("com.example.Foo#foo_bar", "com.example.Foo#read", "com.example.Foo#start"),
				JniExportScanner.scanFile(source));
	}

	@Test
	void scanMapsFilesToExports(@TempDir Path tempDir) throws Exception {
		Path root = tempDir.resolve("natives");
		Path cpp = root.resolve("profiling/foo.cpp");
		Files.createDirectories(cpp.getParent());
		Files.writeString(cpp, "JNIEXPORT void JNICALL Java_com_example_Foo_run(JNIEnv*, jobject) {}",
				StandardCharsets.UTF_8);
		Files.writeString(root.resolve("profiling/empty.cpp"), "int nothing;", StandardCharsets.UTF_8);

		Map<String, Set<String>> exports = JniExportScanner.scan(tempDir, List.of(root));
		assertEquals(Map.of("natives/profiling/foo.cpp", Set.of("com.example.Foo#run")), exports);
	}

	@Test
	void scanIncludesHeaderFilesAndSharedNativeExtensions(@TempDir Path tempDir) throws Exception {
		Path root = tempDir.resolve("natives");
		Path header = root.resolve("profiling/foo.h");
		Path headerxx = root.resolve("profiling/bar.hxx");
		Path sourcePlus = root.resolve("profiling/baz.c++");
		Files.createDirectories(header.getParent());
		Files.writeString(header, "void Java_com_example_Header_run(JNIEnv*, jobject) {}", StandardCharsets.UTF_8);
		Files.writeString(headerxx, "void Java_com_example_Headerxx_run(JNIEnv*, jobject) {}", StandardCharsets.UTF_8);
		Files.writeString(sourcePlus, "void Java_com_example_SourcePlus_run(JNIEnv*, jobject) {}",
				StandardCharsets.UTF_8);

		Map<String, Set<String>> exports = JniExportScanner.scan(tempDir, List.of(root));
		assertEquals(Map.of("natives/profiling/foo.h", Set.of("com.example.Header#run"), "natives/profiling/bar.hxx",
				Set.of("com.example.Headerxx#run"), "natives/profiling/baz.c++", Set.of("com.example.SourcePlus#run")),
				exports);
	}

	@Test
	void scannerKeysUseTheSharedNativeUnitConvention(@TempDir Path tempDir) throws Exception {
		Path root = tempDir.resolve("native");
		Path f = root.resolve("lib.cpp");
		Files.createDirectories(root);
		Files.writeString(f, "void Java_A_b() {}", StandardCharsets.UTF_8);
		Map<String, Set<String>> exports = JniExportScanner.scan(tempDir, List.of(root));
		assertTrue(exports.containsKey("native/lib.cpp"));
	}
}
