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

/**
 * Build-time GLSL to SPIR-V compiler of the fixed-function shaders (module C), run by the {@code compileShaders} task of
 * shaders.gradle with the lwjgl-shaderc natives of the build host.
 *
 * <p>Usage: {@code ShaderCompiler <shader-source-dir> <output-dir>}. Compiles {@code ff.vert} and {@code ff.frag} twice each,
 * plain and with the macro {@code FLAT} (flat shading), into {@code ff.vert.spv}, {@code ff_flat.vert.spv}, {@code ff.frag.spv}
 * and {@code ff_flat.frag.spv}. The target is Vulkan 1.1 (SPIR-V 1.3); every module is also run through the SPIR-V validator.
 * Exits with status 1 and the compiler log on stderr when anything fails.
 */
public final class ShaderCompiler {
    private record Job(String source, String output, int kind, boolean flat) {
    }

    private static final Job[] JOBS = {
            new Job("ff.vert", "ff.vert.spv", Shaderc.shaderc_vertex_shader, false),
            new Job("ff.vert", "ff_flat.vert.spv", Shaderc.shaderc_vertex_shader, true),
            new Job("ff.frag", "ff.frag.spv", Shaderc.shaderc_fragment_shader, false),
            new Job("ff.frag", "ff_flat.frag.spv", Shaderc.shaderc_fragment_shader, true),
    };

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
        deleteStaleOutputs(outputDir);

        long compiler = Shaderc.shaderc_compiler_initialize();
        if (compiler == 0L) {
            System.err.println("shaderc_compiler_initialize failed (are the lwjgl-shaderc natives of this platform on the class path?)");
            System.exit(1);
        }
        boolean ok = true;
        try {
            for (Job job : JOBS) {
                ok &= compile(compiler, job, inputDir.resolve(job.source()), outputDir.resolve(job.output()));
            }
        } finally {
            Shaderc.shaderc_compiler_release(compiler);
        }
        if (!ok) {
            System.exit(1);
        }
    }

    /** The output directory belongs to this tool: drop modules of earlier runs so that renamed or removed shaders do not linger. */
    private static void deleteStaleOutputs(Path outputDir) throws IOException {
        try (DirectoryStream<Path> stale = Files.newDirectoryStream(outputDir, "*.spv")) {
            for (Path file : stale) {
                Files.delete(file);
            }
        }
    }

    private static boolean compile(long compiler, Job job, Path source, Path output) throws IOException {
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
        try {
            Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_1);
            Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_performance);
            if (job.flat()) {
                // a macro option keeps "#version 450" the first line of the source (prepending "#define FLAT" would break it)
                Shaderc.shaderc_compile_options_add_macro_definition(options, "FLAT", "1");
            }

            long result = Shaderc.shaderc_compile_into_spv(compiler, text, job.kind(), name, "main", options);
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
                Files.write(output, bytes);
                System.out.println("Compiled " + name + " -> " + output.getFileName() + " (" + bytes.length + " bytes)");
                return true;
            } finally {
                Shaderc.shaderc_result_release(result);
            }
        } finally {
            Shaderc.shaderc_compile_options_release(options);
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
