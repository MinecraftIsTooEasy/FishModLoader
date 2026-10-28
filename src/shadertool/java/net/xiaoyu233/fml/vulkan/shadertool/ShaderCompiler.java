package net.xiaoyu233.fml.vulkan.shadertool;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.shaderc.SPV;
import org.lwjgl.util.shaderc.SPVDiagnostic;
import org.lwjgl.util.shaderc.Shaderc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.lwjgl.util.shaderc.ShadercIncludeResolve;
import org.lwjgl.util.shaderc.ShadercIncludeResolveI;
import org.lwjgl.util.shaderc.ShadercIncludeResult;
import org.lwjgl.util.shaderc.ShadercIncludeResultRelease;
import org.lwjgl.util.shaderc.ShadercIncludeResultReleaseI;
import org.lwjgl.system.MemoryUtil;

/**
 * Build-time GLSL to SPIR-V compiler of the fixed-function shaders (module C), run by the {@code compileShaders} task of
 * shaders.gradle with the lwjgl-shaderc natives of the build host.
 *
 * <p>Usage: {@code ShaderCompiler <shader-source-dir> <output-dir>}. Compiles {@code ff.vert} and {@code ff.frag} twice each,
 * plain and with the macro {@code FLAT} (flat shading), into {@code ff.vert.spv}, {@code ff_flat.vert.spv}, {@code ff.frag.spv}
 * and {@code ff_flat.frag.spv}. The target is Vulkan 1.1 (SPIR-V 1.3); every module is also run through the SPIR-V validator.
 * Exits with status 1 and the compiler log on stderr when anything fails.
 *
 * <p>Native renderer jobs (ARCHITECTURE-NATIVE 1.9): when the system property {@code fml.shadertool.manifestDir} names a directory, every
 * {@code *.txt} file in it is read in name order and their lines are concatenated into one job list. A line is
 * {@code <source> <output> [NAME[=VALUE] ...]} (paths relative to the shader source directory and the output directory, stage from the
 * extension {@code .vert}/{@code .frag}/{@code .comp}); {@code #} starts a comment. {@code #include "file"} is resolved relative to the
 * including file (GL_GOOGLE_include_directive) and stays inside the shader source directory. Only {@code *.spv} files that no job
 * produces are deleted from the output directory.
 */
public final class ShaderCompiler {
    private record Job(String source, String output, int kind, boolean flat, List<String[]> macros) {
        Job(String source, String output, int kind, boolean flat) {
            this(source, output, kind, flat, List.of());
        }
    }

    private static final Job[] FF_JOBS = {
            new Job("ff.vert", "ff.vert.spv", Shaderc.shaderc_vertex_shader, false),
            new Job("ff.vert", "ff_flat.vert.spv", Shaderc.shaderc_vertex_shader, true),
            new Job("ff.frag", "ff.frag.spv", Shaderc.shaderc_fragment_shader, false),
            new Job("ff.frag", "ff_flat.frag.spv", Shaderc.shaderc_fragment_shader, true),
    };

    private static final String MANIFEST_DIR_PROPERTY = "fml.shadertool.manifestDir";

    private ShaderCompiler() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("Usage: ShaderCompiler <shader-source-dir> <output-dir>");
            System.exit(2);
        }
        Path inputDir = Paths.get(args[0]).toAbsolutePath();
        Path outputDir = Paths.get(args[1]).toAbsolutePath();
        if (!Files.isDirectory(inputDir)) {
            System.err.println("Shader source directory does not exist: " + inputDir);
            System.exit(1);
        }
        Files.createDirectories(outputDir);
        List<Job> jobs = new ArrayList<>(List.of(FF_JOBS));
        String manifestDir = System.getProperty(MANIFEST_DIR_PROPERTY);
        if (manifestDir != null && !manifestDir.isBlank()) {
            try {
                jobs.addAll(readManifests(Paths.get(manifestDir).toAbsolutePath()));
            } catch (IllegalArgumentException e) {
                System.err.println("Shader manifest error: " + e.getMessage());
                System.exit(1);
            }
        }
        deleteStaleOutputs(outputDir, jobs);

        long compiler = Shaderc.shaderc_compiler_initialize();
        if (compiler == 0L) {
            System.err.println("shaderc_compiler_initialize failed (are the lwjgl-shaderc natives of this platform on the class path?)");
            System.exit(1);
        }
        boolean ok = true;
        try {
            for (Job job : jobs) {
                ok &= compile(compiler, job, inputDir, inputDir.resolve(job.source()), outputDir.resolve(job.output()));
            }
        } finally {
            Shaderc.shaderc_compiler_release(compiler);
        }
        if (!ok) {
            System.exit(1);
        }
    }

    /**
     * Reads {@code manifestDir/*.txt} in name order into one job list. A missing directory is an empty list (a tree without native
     * shaders); a malformed line is an {@link IllegalArgumentException} naming file and line.
     */
    static List<Job> readManifests(Path manifestDir) throws IOException {
        List<Job> jobs = new ArrayList<>();
        if (!Files.isDirectory(manifestDir)) {
            return jobs;
        }
        List<Path> files;
        try (Stream<Path> list = Files.list(manifestDir)) {
            files = list.filter(p -> p.getFileName().toString().endsWith(".txt")).sorted().toList();
        }
        for (Path file : files) {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                int hash = line.indexOf('#');
                if (hash >= 0) {
                    line = line.substring(0, hash);
                }
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] words = line.split("\\s+");
                String where = file.getFileName() + ":" + (i + 1);
                if (words.length < 2) {
                    throw new IllegalArgumentException(where + ": expected '<source> <output> [NAME[=VALUE] ...]', got '" + line + "'");
                }
                String source = words[0];
                String output = words[1];
                if (!output.endsWith(".spv") || output.startsWith("/") || output.contains("..")) {
                    throw new IllegalArgumentException(where + ": output must be a relative .spv path, got '" + output + "'");
                }
                int kind = stageOf(source, where);
                List<String[]> macros = new ArrayList<>();
                for (int w = 2; w < words.length; w++) {
                    int eq = words[w].indexOf('=');
                    macros.add(eq < 0 ? new String[] {words[w], "1"} : new String[] {words[w].substring(0, eq), words[w].substring(eq + 1)});
                }
                jobs.add(new Job(source, output, kind, false, macros));
            }
        }
        return jobs;
    }

    private static int stageOf(String source, String where) {
        String lower = source.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".vert")) {
            return Shaderc.shaderc_vertex_shader;
        }
        if (lower.endsWith(".frag")) {
            return Shaderc.shaderc_fragment_shader;
        }
        if (lower.endsWith(".comp")) {
            return Shaderc.shaderc_compute_shader;
        }
        throw new IllegalArgumentException(where + ": unknown shader stage for '" + source + "' (.vert, .frag or .comp)");
    }

    /** The output directory belongs to this tool: drop modules of earlier runs that no job produces, so renamed or removed shaders do not linger. */
    private static void deleteStaleOutputs(Path outputDir, List<Job> jobs) throws IOException {
        Set<Path> expected = new HashSet<>();
        for (Job job : jobs) {
            expected.add(outputDir.resolve(job.output()).normalize());
        }
        List<Path> stale;
        try (Stream<Path> walk = Files.walk(outputDir)) {
            stale = walk.filter(p -> p.getFileName().toString().endsWith(".spv") && Files.isRegularFile(p)
                    && !expected.contains(p.normalize())).toList();
        }
        for (Path file : stale) {
            Files.delete(file);
        }
    }

    /** Resolves {@code #include "x"} relative to the including file, confined to the shader source root. */
    private static final class Includer implements ShadercIncludeResolveI {
        private final Path root;

        Includer(Path root) {
            this.root = root.normalize();
        }

        @Override
        public long invoke(long userData, long requestedSource, int type, long requestingSource, long includeDepth) {
            String requested = MemoryUtil.memUTF8(requestedSource);
            String requesting = MemoryUtil.memUTF8(requestingSource);
            ShadercIncludeResult result = ShadercIncludeResult.calloc();
            try {
                Path base = root.resolve(requesting).getParent();
                Path file = (type == Shaderc.shaderc_include_type_relative ? base.resolve(requested) : root.resolve(requested)).normalize();
                String name;
                String content;
                if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                    name = "";
                    content = "include not found or outside the shader directory: " + requested + " (from " + requesting + ")";
                } else {
                    name = root.relativize(file).toString().replace('\\', '/');
                    content = Files.readString(file);
                }
                java.nio.ByteBuffer nameBytes = MemoryUtil.memUTF8(name, false);
                java.nio.ByteBuffer contentBytes = MemoryUtil.memUTF8(content, false);
                result.source_name(nameBytes).content(contentBytes).user_data(0L);
            } catch (IOException e) {
                java.nio.ByteBuffer msg = MemoryUtil.memUTF8(String.valueOf(e), false);
                result.source_name(MemoryUtil.memUTF8("", false)).content(msg);
            }
            return result.address();
        }

    }

    /** Frees what {@link Includer} allocated for one include result. */
    private static final class IncludeReleaser implements ShadercIncludeResultReleaseI {
        @Override
        public void invoke(long userData, long includeResult) {
            ShadercIncludeResult result = ShadercIncludeResult.create(includeResult);
            MemoryUtil.memFree(result.source_name());
            MemoryUtil.memFree(result.content());
            result.free();
        }
    }

    private static boolean compile(long compiler, Job job, Path root, Path source, Path output) throws IOException {
        if (!Files.isRegularFile(source)) {
            System.err.println("Shader source not found: " + source);
            return false;
        }
        String text = Files.readString(source);
        // diagnostics name the output so that the two variants of one source can be told apart
        String name = job.output().substring(0, job.output().length() - ".spv".length());

        long options = Shaderc.shaderc_compile_options_initialize();
        if (options == 0L) {
            System.err.println("shaderc_compile_options_initialize failed");
            return false;
        }
        ShadercIncludeResolve resolve = null;
        ShadercIncludeResultRelease release = null;
        try {
            Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_1);
            Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_performance);
            for (String[] macro : job.macros()) {
                Shaderc.shaderc_compile_options_add_macro_definition(options, macro[0], macro[1]);
            }
            Includer includer = new Includer(root);
            resolve = ShadercIncludeResolve.create(includer);
            release = ShadercIncludeResultRelease.create(new IncludeReleaser());
            Shaderc.shaderc_compile_options_set_include_callbacks(options, resolve, release, 0L);
            if (job.flat()) {
                // a macro option keeps "#version 450" the first line of the source (prepending "#define FLAT" would break it)
                Shaderc.shaderc_compile_options_add_macro_definition(options, "FLAT", "1");
            }

            // the input name is the path below the source root: #include "..." resolves relative to it
            String inputName = root.relativize(source.normalize()).toString().replace('\\', '/');
            long result = Shaderc.shaderc_compile_into_spv(compiler, text, job.kind(), inputName, "main", options);
            if (result == 0L) {
                System.err.println("shaderc_compile_into_spv returned no result for " + name);
                return false;
            }
            try {
                if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
                    System.err.println("Shader compilation FAILED: " + name + "\n" + Shaderc.shaderc_result_get_error_message(result));
                    return false;
                }
                if (Shaderc.shaderc_result_get_num_warnings(result) > 0) {
                    System.err.println("Warnings of " + name + ":\n" + Shaderc.shaderc_result_get_error_message(result));
                }
                ByteBuffer spirv = Shaderc.shaderc_result_get_bytes(result);
                if (spirv == null || spirv.remaining() < 20 || (spirv.remaining() & 3) != 0) {
                    System.err.println("Shader compilation produced an invalid SPIR-V blob for " + name);
                    return false;
                }
                if (!validate(name, spirv)) {
                    return false;
                }
                byte[] bytes = new byte[spirv.remaining()];
                spirv.get(bytes);      // the buffer points into the result object, copy before it is released
                Files.createDirectories(output.getParent());
                Files.write(output, bytes);
                System.out.println("Compiled " + name + " -> " + output.getFileName() + " (" + bytes.length + " bytes)");
                return true;
            } finally {
                Shaderc.shaderc_result_release(result);
            }
        } finally {
            Shaderc.shaderc_compile_options_release(options);
            if (resolve != null) {
                resolve.free();
            }
            if (release != null) {
                release.free();
            }
        }
    }

    /** Runs the SPIRV-Tools validator (bundled with lwjgl-shaderc) against the Vulkan 1.1 rules. */
    private static boolean validate(String name, ByteBuffer spirv) {
        long context = SPV.spvContextCreate(SPV.SPV_ENV_VULKAN_1_1);
        if (context == 0L) {
            System.err.println("spvContextCreate failed, " + name + " was not validated");
            return true;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer words = spirv.asIntBuffer();
            PointerBuffer pDiagnostic = stack.mallocPointer(1);
            int status = SPV.spvValidateBinary(context, words, pDiagnostic);
            if (status == SPV.SPV_SUCCESS) {
                return true;
            }
            SPVDiagnostic diagnostic = SPVDiagnostic.createSafe(pDiagnostic.get(0));
            System.err.println("SPIR-V validation FAILED: " + name + " (status " + status + ")"
                    + (diagnostic != null ? "\n" + diagnostic.errorString() : ""));
            if (diagnostic != null) {
                SPV.spvDiagnosticDestroy(diagnostic);
            }
            return false;
        } finally {
            SPV.spvContextDestroy(context);
        }
    }
}
