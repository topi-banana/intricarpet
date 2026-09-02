package me.lntricate.intricarpet;

import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * `intricarpet.mixins.json` names exactly the mixin classes this selection compiled — no more and
 * no fewer.
 *
 * <p>It is the one thing about this mod that nothing else checks, and both directions of getting it
 * wrong are silent until they are not. `build.rhai` builds that list by hand, and the source
 * decides which classes exist by `#[cfg]`; two places reading one boundary. Name a class the
 * selection blanked and Mixin refuses the whole configuration at load — the mod does not start, on
 * a jar that `jals build`, `check-remap.py` and `regenerate.py --check` all passed. Forget to name
 * one and it is simply never applied: the jar loads, the game runs, and the feature is quietly
 * gone.
 *
 * <p>What is <em>not</em> here, deliberately: that a mixin's target type exists. `@Mixin(X.class)`
 * is a class literal, so `javac` already resolved it against this release's game jar and this
 * release's Carpet — a missing target is a compile error, not a runtime one, and re-asserting it at
 * runtime is not possible anyway (Mixin's annotations are `CLASS`-retention; they are read from
 * bytecode by the transformer and are invisible to reflection).
 *
 * <p>This costs no client. It wants the compiled classes and this build's own rendered resource,
 * so it runs in every cell of the matrix whether or not that cell selected `client-test`.
 *
 * <p><b>It reads a directory that accumulates.</b> `jals test` does not clear `[test] classes-dir`
 * when the feature selection changes, so a type the new selection blanks survives as a class file
 * from the old one — and stays on the test classpath for every test in the run, not just this one.
 * Selecting 1.21.5 and then 1.17.1 in one tree reproduces it. That makes a red verdict here correct
 * rather than spurious: the JVM really did have a foreign release's class on its classpath. It also
 * makes the verdict ambiguous about *why*, which is what the failure messages below say out loud.
 * `jals clean` is the discriminator. No CI cell can hit it — every one is a fresh checkout building
 * a single selection.
 */
public final class MixinConfigTest {
    /** The compiled mixin package, spelled as a classpath resource rather than as a path. */
    private static final String MIXIN_PACKAGE = "me/lntricate/intricarpet/mixins";

    /**
     * Where `build.rhai` renders the configuration, relative to the project root — which is the
     * working directory of a `jals test` JVM.
     *
     * <p>Named as a path because a resource root is a packaging input: `[build] resource-dirs`
     * decides what goes into the jar, not what goes onto a test classpath. If jals ever puts it on
     * the classpath, or moves where a build script writes, this test says so by name rather than by
     * quietly checking nothing.
     */
    private static final String RENDERED_CONFIG =
        "target/jals/build/rhai/out/resources/intricarpet.mixins.json";

    private MixinConfigTest() {}

    #[test]
    static void theConfigNamesExactlyTheMixinsThisSelectionCompiled() throws Exception {
        Set<String> declared = declaredMixins();
        Set<String> compiled = compiledMixins();

        Set<String> missing = new TreeSet<>(declared);
        missing.removeAll(compiled);
        assert missing.isEmpty()
            : "the config names classes this selection did not compile, which is a configuration"
                + " Mixin refuses whole: "
                + missing;

        Set<String> unnamed = new TreeSet<>(compiled);
        unnamed.removeAll(declared);
        // Two causes, and this test cannot tell them apart — so it names both, stale first, because
        // that is the one a reader hits without having changed anything. See the class comment.
        assert unnamed.isEmpty()
            : "compiled and never named: "
                + unnamed
                + ". Either the classes directory still holds"
                + " classes from a previous `--features` selection — `jals test` does not clear it"
                + " when the selection changes, so a type this selection blanks survives from the"
                + " last one; `jals clean` settles it — or `build.rhai` really did forget them, in"
                + " which case they are compiled, packaged and silently never applied.";
    }

    /**
     * `ChunkMapAccessor` is `#[cfg(feature = "since-1.21.5")]` in the source and a `build.feature`
     * branch in `build.rhai`. The test above would catch the two disagreeing; this one says which
     * answer is right, so a run that agreed on the wrong one is still red.
     */
    #[test]
    static void theAccessorIsPresentOnExactlyTheReleasesThatCanCarryIt() throws Exception {
        boolean present = compiledMixins().contains("interactions.ChunkMapAccessor");
        #[cfg(feature = "since-1.21.5")] assert present
            : "1.21.5 and later compile the accessor, so the config may name it";
        #[cfg(not(feature = "since-1.21.5"))] assert !present
            : "before 1.21.5 the accessor is blanked, so naming it would be a load failure. Present"
                + " here means a stale classes directory rather than a config that drifted: run"
                + " `jals clean` and this selection again.";
    }

    /**
     * The `mixins` array of the rendered configuration.
     *
     * <p>Read with a regex rather than a JSON parser, and that is a statement about the input
     * rather than a shortcut: this file is not somebody's data, it is this build's own output,
     * rendered from `templates/intricarpet.mixins.json` in this repository from a list of bare
     * dotted names. A parser would buy tolerance of input shapes that would themselves be the bug.
     */
    private static Set<String> declaredMixins() throws Exception {
        Path config = Paths.get(RENDERED_CONFIG);
        assert Files.isRegularFile(config)
            : "the build script rendered its mixin config at " + RENDERED_CONFIG;
        String rendered = new String(Files.readAllBytes(config), StandardCharsets.UTF_8);

        Matcher array = Pattern.compile("\"mixins\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(rendered);
        assert array.find() : "the rendered config carries a `mixins` array";

        Set<String> declared = new LinkedHashSet<>();
        Matcher name = Pattern.compile("\"([^\"]+)\"").matcher(array.group(1));
        while (name.find()) {
            assert declared.add(name.group(1)) : name.group(1) + " is named once, not twice";
        }
        assert !declared.isEmpty() : "the rendered config names at least one mixin";
        return declared;
    }

    /**
     * Every mixin class this selection compiled, as the configuration spells them: dotted names
     * relative to the configuration's own `package`.
     *
     * <p>Read off the classpath rather than from a list, because a list here would be a third copy
     * of the one in `build.rhai` and the source's own `#[cfg]`s — and the copy that goes stale
     * silently is the one whose omissions nothing can see.
     */
    private static Set<String> compiledMixins() throws Exception {
        Set<String> compiled = new TreeSet<>();
        // The loader is read where it is used rather than bound to a local, and the reason is a gap
        // rather than a preference: `jals lint` is unconditionally offline and the `java.lang` table
        // it falls back to has no `ClassLoader` in it, so *naming* the type is a `cannot-resolve`
        // error out of a gate that has no classpath to be an authority with. `String`, `Class`,
        // `Thread` and `System` all resolve; this one does not. Worth an issue against jals — until
        // then, not writing the name costs nothing.
        Enumeration<URL> roots = MixinConfigTest.class.getClassLoader().getResources(MIXIN_PACKAGE);
        while (roots.hasMoreElements()) {
            URL root = roots.nextElement();
            // A classes *directory*, which is what `[build] classes-dir` and `[test] classes-dir`
            // both are. Nothing on this classpath could hold the mod's own mixins in a jar — `jals
            // build` writes one and `jals test` does not run it — so a non-`file:` root here is
            // some other package of the same name and is not this test's business.
            if (!"file".equals(root.getProtocol())) {
                continue;
            }
            Path directory = Paths.get(root.toURI());
            List<Path> classFiles;
            try (Stream<Path> tree = Files.walk(directory)) {
                classFiles =
                    tree.filter(path -> path.toString().endsWith(".class"))
                        .collect(Collectors.toList());
            }
            for (Path classFile : classFiles) {
                String relative = directory.relativize(classFile).toString();
                String name =
                    relative.substring(0, relative.length() - ".class".length())
                        .replace(File.separatorChar, '.');
                // A nested class is carried by its outer one and is never named in a config.
                if (name.indexOf('$') < 0) {
                    compiled.add(name);
                }
            }
        }
        assert !compiled.isEmpty() : "the compiled mixin package is on the test classpath";
        return compiled;
    }
}
