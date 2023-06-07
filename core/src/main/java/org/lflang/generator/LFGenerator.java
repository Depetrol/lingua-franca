package org.lflang.generator;

import com.google.inject.Inject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.xtext.generator.AbstractGenerator;
import org.eclipse.xtext.generator.IFileSystemAccess2;
import org.eclipse.xtext.generator.IGeneratorContext;
import org.eclipse.xtext.util.RuntimeIOException;
import org.lflang.AttributeUtils;
import org.lflang.ErrorReporter;
import org.lflang.FileConfig;
import org.lflang.Target;
import org.lflang.analyses.uclid.UclidGenerator;
import org.lflang.ast.ASTUtils;
import org.lflang.federated.generator.FedASTUtils;
import org.lflang.federated.generator.FedFileConfig;
import org.lflang.federated.generator.FedGenerator;
import org.lflang.generator.c.CFileConfig;
import org.lflang.generator.c.CGenerator;
import org.lflang.generator.python.PyFileConfig;
import org.lflang.generator.python.PythonGenerator;
import org.lflang.lf.Attribute;
import org.lflang.lf.Reactor;
import org.lflang.scoping.LFGlobalScopeProvider;

/**
 * Generates code from your model files on save.
 *
 * <p>See https://www.eclipse.org/Xtext/documentation/303_runtime_concepts.html#code-generation
 */
public class LFGenerator extends AbstractGenerator {

  @Inject private LFGlobalScopeProvider scopeProvider;

  // Indicator of whether generator errors occurred.
  protected boolean generatorErrorsOccurred = false;

  /**
   * Create a target-specific FileConfig object in Kotlin
   *
   * <p>Since the CppFileConfig and TSFileConfig class are implemented in Kotlin, the classes are
   * not visible from all contexts. If the RCA is run from within Eclipse via "Run as Eclipse
   * Application", the Kotlin classes are unfortunately not available at runtime due to bugs in the
   * Eclipse Kotlin plugin. (See
   * https://stackoverflow.com/questions/68095816/is-ist-possible-to-build-mixed-kotlin-and-java-applications-with-a-recent-eclips)
   *
   * <p>If the FileConfig class is found, this method returns an instance. Otherwise, it returns an
   * Instance of FileConfig.
   *
   * @return A FileConfig object in Kotlin if the class can be found.
   * @throws IOException If the file config could not be created properly
   */
  public static FileConfig createFileConfig(
      Resource resource, Path srcGenBasePath, boolean useHierarchicalBin) {

    final Target target = Target.fromDecl(ASTUtils.targetDecl(resource));
    assert target != null;

    // Since our Eclipse Plugin uses code injection via guice, we need to
    // play a few tricks here so that FileConfig does not appear as an
    // import. Instead, we look the class up at runtime and instantiate it if
    // found.
    try {
      if (FedASTUtils.findFederatedReactor(resource) != null) {
        return new FedFileConfig(resource, srcGenBasePath, useHierarchicalBin);
      }
      switch (target) {
        case CCPP:
        case C:
          return new CFileConfig(resource, srcGenBasePath, useHierarchicalBin);
        case Python:
          return new PyFileConfig(resource, srcGenBasePath, useHierarchicalBin);
        case CPP:
        case Rust:
        case TS:
          String className =
              "org.lflang.generator."
                  + target.packageName
                  + "."
                  + target.classNamePrefix
                  + "FileConfig";
          try {
            return (FileConfig)
                Class.forName(className)
                    .getDeclaredConstructor(Resource.class, Path.class, boolean.class)
                    .newInstance(resource, srcGenBasePath, useHierarchicalBin);
          } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Exception instantiating " + className, e.getCause());
          }
        default:
          throw new RuntimeException(
              "Could not find FileConfig implementation for target " + target);
      }
    } catch (IOException e) {
      throw new RuntimeException(
          "Unable to create FileConfig object for target " + target + ": " + e.getStackTrace());
    }
  }

  /**
   * Create a generator object for the given target. Returns null if the generator could not be
   * created.
   */
  private GeneratorBase createGenerator(LFGeneratorContext context) {
    final Target target = Target.fromDecl(ASTUtils.targetDecl(context.getFileConfig().resource));
    assert target != null;
    return switch (target) {
      case C -> new CGenerator(context, false);
      case CCPP -> new CGenerator(context, true);
      case Python -> new PythonGenerator(context);
      case CPP, TS, Rust -> createKotlinBaseGenerator(target, context);
        // If no case matched, then throw a runtime exception.
      default -> throw new RuntimeException("Unexpected target!");
    };
  }

  /**
   * Create a code generator in Kotlin.
   *
   * <p>Since the CppGenerator and TSGenerator class are implemented in Kotlin, the classes are not
   * visible from all contexts. If the RCA is run from within Eclipse via "Run as Eclipse
   * Application", the Kotlin classes are unfortunately not available at runtime due to bugs in the
   * Eclipse Kotlin plugin. (See
   * https://stackoverflow.com/questions/68095816/is-ist-possible-to-build-mixed-kotlin-and-java-applications-with-a-recent-eclips)
   * In this case, the method returns null
   *
   * @return A Kotlin Generator object if the class can be found
   */
  private GeneratorBase createKotlinBaseGenerator(Target target, LFGeneratorContext context) {
    // Since our Eclipse Plugin uses code injection via guice, we need to
    // play a few tricks here so that Kotlin FileConfig and
    // Kotlin Generator do not appear as an import. Instead, we look the
    // class up at runtime and instantiate it if found.
    String classPrefix =
        "org.lflang.generator." + target.packageName + "." + target.classNamePrefix;
    try {
      Class<?> generatorClass = Class.forName(classPrefix + "Generator");
      Constructor<?> ctor =
          generatorClass.getDeclaredConstructor(
              LFGeneratorContext.class, LFGlobalScopeProvider.class);

      return (GeneratorBase) ctor.newInstance(context, scopeProvider);
    } catch (ReflectiveOperationException e) {
      generatorErrorsOccurred = true;
      context
          .getErrorReporter()
          .reportError(
              "The code generator for the "
                  + target
                  + " target could not be found. "
                  + "This is likely because you built Epoch using "
                  + "Eclipse. The "
                  + target
                  + " code generator is written in Kotlin and, unfortunately, the plugin that"
                  + " Eclipse uses for compiling Kotlin code is broken. Please consider building"
                  + " Epoch using Maven.\n"
                  + "For step-by-step instructions, see: "
                  + "https://github.com/icyphy/lingua-franca/wiki/Running-Lingua-Franca-IDE-%28Epoch%29-with-Kotlin-based-Code-Generators-Enabled-%28without-Eclipse-Environment%29");
      return null;
    }
  }

  @Override
  public void doGenerate(Resource resource, IFileSystemAccess2 fsa, IGeneratorContext context) {
    final LFGeneratorContext lfContext;
    if (context instanceof LFGeneratorContext) {
      lfContext = (LFGeneratorContext) context;
    } else {
      lfContext = LFGeneratorContext.lfGeneratorContextOf(resource, fsa, context);
    }

    // The fastest way to generate code is to not generate any code.
    if (lfContext.getMode() == LFGeneratorContext.Mode.LSP_FAST) return;

    if (FedASTUtils.findFederatedReactor(resource) != null) {
      try {
        generatorErrorsOccurred = (new FedGenerator(lfContext)).doGenerate(resource, lfContext);
      } catch (IOException e) {
        throw new RuntimeIOException("Error during federated code generation", e);
      }

    } else {

      // If "-c" or "--clean" is specified, delete any existing generated directories.
      cleanIfNeeded(lfContext);

      // If @property annotations are used, run the LF verifier.
      runVerifierIfPropertiesDetected(resource, lfContext);

      final GeneratorBase generator = createGenerator(lfContext);

      if (generator != null) {
        generator.doGenerate(resource, lfContext);
        generatorErrorsOccurred = generator.errorsOccurred();
      }
    }
    final ErrorReporter errorReporter = lfContext.getErrorReporter();
    if (errorReporter instanceof LanguageServerErrorReporter) {
      ((LanguageServerErrorReporter) errorReporter).publishDiagnostics();
    }
  }

  /** Return true if errors occurred in the last call to doGenerate(). */
  public boolean errorsOccurred() {
    return generatorErrorsOccurred;
  }

  /**
   * Check if a clean was requested from the standalone compiler and perform
   * the clean step.
   * 
   * FIXME: the signature can be reduced to only take context.
   */
  protected void cleanIfNeeded(LFGeneratorContext context) {
    if (context.getArgs().containsKey("clean")) {
      try {
        context.getFileConfig().doClean();
      } catch (IOException e) {
        System.err.println("WARNING: IO Error during clean");
      }
    }
  }

  /**
   * Check if @property is used. If so, instantiate a UclidGenerator.
   * The verification model needs to be generated before the target code
   * since code generation changes LF program (desugar connections, etc.).
   */
  private void runVerifierIfPropertiesDetected(Resource resource, LFGeneratorContext lfContext) {
    Reactor main = ASTUtils.getMainReactor(resource);
    List<Attribute> properties = AttributeUtils.getAttributes(main)
                                .stream()
                                .filter(attr -> attr.getAttrName().equals("property"))
                                .collect(Collectors.toList());
    if (properties.size() > 0) {

      System.out.println("*** WARNING: @property is an experimental feature. Use it with caution. ***");

      // Check if Uclid5 and Z3 are installed.
      if (execInstalled("uclid", "--help", "uclid 0.9.5")
          && execInstalled("z3", "--version", "Z3 version")) {
        UclidGenerator uclidGenerator = new UclidGenerator(lfContext, properties);
        // Generate uclid files.
        uclidGenerator.doGenerate(resource, lfContext);
        if (!uclidGenerator.targetConfig.noVerify) {
          // Invoke the generated uclid files.
          uclidGenerator.runner.run();
        } else {
          System.out.println("\"no-verify\" is set to true. Skip checking the verification model.");
        }
      } else {
        System.out.println("*** WARNING: Uclid5 or Z3 is not installed. @property is skipped. ***");
      }
    }
  }

  /**
   * A helper function for checking if a dependency is installed on the command line.
   * 
   * @param binaryName The name of the binary
   * @param arg An argument following the binary name
   * @param expectedSubstring An expected substring in the output
   * @return
   */
  public static boolean execInstalled(String binaryName, String arg, String expectedSubstring) {
    ProcessBuilder processBuilder = new ProcessBuilder(binaryName, arg);
    try {
      Process process = processBuilder.start();
      BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.contains(expectedSubstring)) {
          return true;
        }
      }
    } catch (IOException e) {
      return false; // binary not present
    }
    return false;
  }
}
