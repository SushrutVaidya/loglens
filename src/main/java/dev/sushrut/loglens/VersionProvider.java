package dev.sushrut.loglens;

import picocli.CommandLine.IVersionProvider;

/** Supplies {@code --version} output from the build-injected {@link BuildInfo#VERSION}. */
final class VersionProvider implements IVersionProvider {
    @Override
    public String[] getVersion() {
        return new String[]{"loglens " + BuildInfo.VERSION};
    }
}
