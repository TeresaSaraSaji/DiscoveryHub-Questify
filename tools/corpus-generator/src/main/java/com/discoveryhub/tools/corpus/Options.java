package com.discoveryhub.tools.corpus;

import java.nio.file.Path;

/** Command line options. Defaults produce the committed fixture. */
record Options(Path outDir, int count, long seed, int custodians, int duplicates,
               String postUrl, int batchSize, boolean statsOnly) {

    static final String USAGE = """
            corpus-generator — deterministic synthetic message corpus for DiscoveryHub

              --out <dir>          output directory            (default: fixtures)
              --count <n>          unique messages             (default: 12000)
              --seed <n>           RNG seed                    (default: 20240906)
              --custodians <n>     roster size, 11..26         (default: 24)
              --duplicates <n>     deliberate re-sends         (default: 25)
              --post <url>         POST to an ingestion endpoint instead of writing files
              --batch-size <n>     messages per POST           (default: 200)
              --stats              print statistics only, write nothing
              --help

            Examples:
              corpus-generator --out fixtures
              corpus-generator --post http://localhost:8081/messages --batch-size 250
            """;

    static Options parse(String[] args) {
        Path outDir = Path.of("fixtures");
        int count = 12_000;
        long seed = 20240906L;
        int custodians = 24;
        int duplicates = 25;
        String postUrl = null;
        int batchSize = 200;
        boolean statsOnly = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--out" -> outDir = Path.of(next(args, ++i));
                case "--count" -> count = Integer.parseInt(next(args, ++i));
                case "--seed" -> seed = Long.parseLong(next(args, ++i));
                case "--custodians" -> custodians = Integer.parseInt(next(args, ++i));
                case "--duplicates" -> duplicates = Integer.parseInt(next(args, ++i));
                case "--post" -> postUrl = next(args, ++i);
                case "--batch-size" -> batchSize = Integer.parseInt(next(args, ++i));
                case "--stats" -> statsOnly = true;
                case "--help", "-h" -> {
                    System.out.println(USAGE);
                    System.exit(0);
                }
                default -> throw new IllegalArgumentException("unknown option: " + args[i]);
            }
        }
        if (count < 1) {
            throw new IllegalArgumentException("--count must be positive");
        }
        return new Options(outDir, count, seed, custodians, duplicates, postUrl, batchSize, statsOnly);
    }

    private static String next(String[] args, int i) {
        if (i >= args.length) {
            throw new IllegalArgumentException("missing value for " + args[i - 1]);
        }
        return args[i];
    }
}
