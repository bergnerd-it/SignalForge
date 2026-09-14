package com.bergnerd.signalforge.app.research.backtest;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class BuildIdentityResolver {

    public static final String DEFAULT_ENGINE_VERSION = "2.0.0-M3";

    private String engineVersion = DEFAULT_ENGINE_VERSION;
    private String sourceCommit = "UNKNOWN";
    private boolean dirtyFlag = false;
    private String buildVersion = "1.0.0-SNAPSHOT";
    private String codeFingerprint = "UNKNOWN";

    // Test overrides
    private volatile String overrideEngineVersion = null;
    private volatile String overrideSourceCommit = null;
    private volatile Boolean overrideDirtyFlag = null;
    private volatile String overrideCodeFingerprint = null;

    @PostConstruct
    public void init() {
        resolveFromProperties();
        if ("UNKNOWN".equals(sourceCommit) || sourceCommit.startsWith("${")) {
            resolveFromGit();
        }
        log.info("SignalForge build identity resolved: engineVersion={}, sourceCommit={}, dirtyFlag={}, buildVersion={}, codeFingerprint={}",
                engineVersion, sourceCommit, dirtyFlag, buildVersion, codeFingerprint);
    }

    private void resolveFromProperties() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("signalforge-build.properties")) {
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                String c = props.getProperty("git.commit");
                if (c != null && !c.isBlank() && !c.startsWith("${")) {
                    this.sourceCommit = c.trim();
                }
                String d = props.getProperty("git.dirty");
                if (d != null && !d.isBlank() && !d.startsWith("${")) {
                    this.dirtyFlag = Boolean.parseBoolean(d.trim());
                }
                String v = props.getProperty("build.version");
                if (v != null && !v.isBlank() && !v.startsWith("${")) {
                    this.buildVersion = v.trim();
                }
                String fingerprint = props.getProperty("code.sha256");
                if (fingerprint != null && fingerprint.matches("[a-f0-9]{64}")) {
                    this.codeFingerprint = fingerprint;
                }
            }
        } catch (Exception e) {
            log.warn("Could not load signalforge-build.properties: {}", e.getMessage());
        }

        // Allow system property overrides
        String sysCommit = System.getProperty("signalforge.engine.source-commit");
        if (sysCommit != null && !sysCommit.isBlank()) {
            this.sourceCommit = sysCommit.trim();
        }
        String sysDirty = System.getProperty("signalforge.engine.dirty-flag");
        if (sysDirty != null && !sysDirty.isBlank()) {
            this.dirtyFlag = Boolean.parseBoolean(sysDirty.trim());
        }
        String sysVersion = System.getProperty("signalforge.engine.version");
        if (sysVersion != null && !sysVersion.isBlank()) {
            this.engineVersion = sysVersion.trim();
        }
    }

    private void resolveFromGit() {
        try {
            Process pCommit = new ProcessBuilder("git", "rev-parse", "HEAD").redirectErrorStream(true).start();
            boolean finished = pCommit.waitFor(2, TimeUnit.SECONDS);
            if (finished && pCommit.exitValue() == 0) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(pCommit.getInputStream(), StandardCharsets.UTF_8))) {
                    String line = reader.readLine();
                    if (line != null && !line.isBlank()) {
                        this.sourceCommit = line.trim();
                    }
                }
            }

            Process pStatus = new ProcessBuilder("git", "status", "--porcelain").redirectErrorStream(true).start();
            boolean sFinished = pStatus.waitFor(2, TimeUnit.SECONDS);
            if (sFinished && pStatus.exitValue() == 0) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(pStatus.getInputStream(), StandardCharsets.UTF_8))) {
                    this.dirtyFlag = reader.readLine() != null;
                }
            }
        } catch (Exception e) {
            log.debug("Runtime git resolution skipped: {}", e.getMessage());
        }
    }

    public String getEngineVersion() {
        return overrideEngineVersion != null ? overrideEngineVersion : engineVersion;
    }

    public String getSourceCommit() {
        return overrideSourceCommit != null ? overrideSourceCommit : sourceCommit;
    }

    public boolean isDirty() {
        return overrideDirtyFlag != null ? overrideDirtyFlag : dirtyFlag;
    }

    public String getBuildVersion() {
        return buildVersion;
    }

    public String getCodeFingerprint() {
        return overrideCodeFingerprint != null ? overrideCodeFingerprint : codeFingerprint;
    }

    public void setTestOverride(String engineVersion, String sourceCommit, Boolean dirty) {
        this.overrideEngineVersion = engineVersion;
        this.overrideSourceCommit = sourceCommit;
        this.overrideDirtyFlag = dirty;
    }

    public void clearTestOverride() {
        this.overrideEngineVersion = null;
        this.overrideSourceCommit = null;
        this.overrideDirtyFlag = null;
        this.overrideCodeFingerprint = null;
    }

    public void setTestCodeFingerprint(String fingerprint) {
        this.overrideCodeFingerprint = fingerprint;
    }
}
