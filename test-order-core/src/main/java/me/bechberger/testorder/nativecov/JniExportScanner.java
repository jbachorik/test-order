package me.bechberger.testorder.nativecov;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import me.bechberger.testorder.TestOrderLogger;

/**
 * Scans native source roots for JNI exported functions and demangles the
 * mechanical {@code Java_<class>_<method>} naming into {@code FQCN#method}
 * member keys.
 *
 * <p>
 * Mangling rules (JNI spec): segments are separated by {@code _}; a literal
 * {@code _} inside a name is escaped as {@code _1}; overloaded methods carry a
 * signature suffix after a double underscore. The parse is mechanical — an
 * exported name whose class part mixes separators ambiguously (e.g. nested
 * classes vs. underscore-containing packages) may demangle to an FQCN that
 * matches no Java member; such entries simply never intersect with the
 * MEMBER-mode dependency index and are harmless.
 */
public final class JniExportScanner {

	/** Matches JNI export identifiers inside native source text. */
	private static final Pattern JNI_FUNCTION = Pattern.compile("\\bJava_[A-Za-z][A-Za-z0-9_]*");

	/**
	 * Files larger than this are skipped (a 5 MB source file is not a JNI map
	 * source).
	 */
	private static final int MAX_FILE_BYTES = 5_000_000;

	private JniExportScanner() {
	}

	/**
	 * Demangles a JNI export identifier (the full {@code Java_...} name) into a
	 * {@code FQCN#methodName} member key, or {@code null} when the name cannot be
	 * demangled.
	 */
	public static String demangle(String jniName) {
		if (jniName == null || !jniName.startsWith("Java_")) {
			return null;
		}
		String body = jniName.substring("Java_".length());
		// Strip the overload signature suffix ("...__II" → "..._method")
		int overload = body.indexOf("__");
		if (overload >= 0) {
			body = body.substring(0, overload);
		}
		// Token-scan: "_1" is a literal underscore inside the current segment,
		// any other "_" ends the current segment.
		List<String> segments = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (int i = 0; i < body.length(); i++) {
			char c = body.charAt(i);
			if (c == '_') {
				if (i + 1 < body.length() && body.charAt(i + 1) == '1') {
					current.append('_');
					i++;
				} else {
					if (current.length() == 0) {
						return null; // empty segment — not a valid mangled name
					}
					segments.add(current.toString());
					current.setLength(0);
				}
			} else if (Character.isJavaIdentifierPart(c)) {
				current.append(c);
			} else {
				return null;
			}
		}
		if (current.length() == 0 || segments.isEmpty()) {
			return null; // method name missing or no class segments
		}
		segments.add(current.toString());
		String method = segments.get(segments.size() - 1);
		String clazz = String.join(".", segments.subList(0, segments.size() - 1));
		return clazz + "#" + method;
	}

	/**
	 * Scans all declared native source roots for JNI exports.
	 *
	 * @param projectRoot
	 *            project root; file keys are produced via
	 *            {@link me.bechberger.testorder.changes.NativeChangeDetector#nativeKey}
	 * @param sourceRoots
	 *            declared native source roots
	 * @return map of native file key → set of demangled {@code FQCN#method} keys
	 */
	public static Map<String, Set<String>> scan(Path projectRoot, List<Path> sourceRoots) {
		Map<String, Set<String>> exports = new TreeMap<>();
		for (Path root : sourceRoots) {
			if (root == null || !Files.isDirectory(root)) {
				continue;
			}
			List<Path> files;
			try (Stream<Path> walk = Files.walk(root)) {
				files = walk.filter(Files::isRegularFile).toList();
			} catch (IOException e) {
				TestOrderLogger.warn("Failed to scan native root {} for JNI exports: {}", root, e.getMessage());
				continue;
			}
			for (Path file : files) {
				String name = file.getFileName().toString();
				if (!name.endsWith(".cpp") && !name.endsWith(".cc") && !name.endsWith(".c") && !name.endsWith(".cxx")
						&& !name.endsWith(".h") && !name.endsWith(".hpp") && !name.endsWith(".hh")) {
					continue;
				}
				try {
					if (Files.size(file) > MAX_FILE_BYTES) {
						continue;
					}
					Set<String> found = scanFile(Files.readString(file, StandardCharsets.UTF_8));
					if (!found.isEmpty()) {
						exports.computeIfAbsent(
								me.bechberger.testorder.changes.NativeChangeDetector.nativeKey(projectRoot, file),
								k -> new TreeSet<>()).addAll(found);
					}
				} catch (IOException e) {
					TestOrderLogger.warn("Failed to read {} for JNI export scan: {}", file, e.getMessage());
				}
			}
		}
		return exports;
	}

	/**
	 * Visible for testing: extracts demangled member keys from one source file's
	 * text.
	 */
	public static Set<String> scanFile(String source) {
		Set<String> result = new TreeSet<>();
		Matcher m = JNI_FUNCTION.matcher(source);
		while (m.find()) {
			String demangled = demangle(m.group());
			if (demangled != null) {
				result.add(demangled);
			}
		}
		return result;
	}
}
