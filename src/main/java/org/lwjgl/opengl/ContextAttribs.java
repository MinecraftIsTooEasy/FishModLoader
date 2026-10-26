package org.lwjgl.opengl;

/**
 * LWJGL 2 API: the GL context wish passed to {@code Display.create} (public API and validation of LWJGL 2.9.0). Owned by
 * module E. There is no GL context in the Vulkan port: the values are kept and otherwise ignored.
 */
public final class ContextAttribs {
    private int majorVersion;
    private int minorVersion;
    private int layerPlane;
    private boolean debug;
    private boolean forwardCompatible;
    private boolean robustAccess;
    private boolean profileCore;
    private boolean profileCompatibility;
    private boolean profileES;
    private boolean loseContextOnReset;
    private boolean contextResetIsolation;

    public ContextAttribs() {
        this(1, 0);
    }

    public ContextAttribs(int majorVersion, int minorVersion) {
        if (majorVersion < 0 || 4 < majorVersion || minorVersion < 0
                || (majorVersion == 4 && 3 < minorVersion)
                || (majorVersion == 3 && 3 < minorVersion)
                || (majorVersion == 2 && 1 < minorVersion)
                || (majorVersion == 1 && 5 < minorVersion)) {
            throw new IllegalArgumentException("Invalid OpenGL version specified: " + majorVersion + '.' + minorVersion);
        }
        this.majorVersion = majorVersion;
        this.minorVersion = minorVersion;
    }

    private ContextAttribs(ContextAttribs attribs) {
        this.majorVersion = attribs.majorVersion;
        this.minorVersion = attribs.minorVersion;
        this.layerPlane = attribs.layerPlane;
        this.debug = attribs.debug;
        this.forwardCompatible = attribs.forwardCompatible;
        this.robustAccess = attribs.robustAccess;
        this.profileCore = attribs.profileCore;
        this.profileCompatibility = attribs.profileCompatibility;
        this.profileES = attribs.profileES;
        this.loseContextOnReset = attribs.loseContextOnReset;
        this.contextResetIsolation = attribs.contextResetIsolation;
    }

    public int getMajorVersion() {
        return majorVersion;
    }

    public int getMinorVersion() {
        return minorVersion;
    }

    public int getLayerPlane() {
        return layerPlane;
    }

    public boolean isDebug() {
        return debug;
    }

    public boolean isForwardCompatible() {
        return forwardCompatible;
    }

    public boolean isProfileCore() {
        return profileCore;
    }

    public boolean isProfileCompatibility() {
        return profileCompatibility;
    }

    public boolean isProfileES() {
        return profileES;
    }

    public ContextAttribs withLayer(int layerPlane) {
        if (layerPlane < 0) {
            throw new IllegalArgumentException("Invalid layer plane specified: " + layerPlane);
        }
        if (layerPlane == this.layerPlane) {
            return this;
        }
        ContextAttribs attribs = new ContextAttribs(this);
        attribs.layerPlane = layerPlane;
        return attribs;
    }

    public ContextAttribs withDebug(boolean debug) {
        if (debug == this.debug) {
            return this;
        }
        ContextAttribs attribs = new ContextAttribs(this);
        attribs.debug = debug;
        return attribs;
    }

    public ContextAttribs withForwardCompatible(boolean forwardCompatible) {
        if (forwardCompatible == this.forwardCompatible) {
            return this;
        }
        ContextAttribs attribs = new ContextAttribs(this);
        attribs.forwardCompatible = forwardCompatible;
        return attribs;
    }

    public ContextAttribs withProfileCore(boolean profileCore) {
        requireProfiles();
        if (profileCore == this.profileCore) {
            return this;
        }
        ContextAttribs attribs = new ContextAttribs(this);
        attribs.profileCore = profileCore;
        if (profileCore) {
            attribs.profileCompatibility = false;
        }
        return attribs;
    }

    public ContextAttribs withProfileCompatibility(boolean profileCompatibility) {
        requireProfiles();
        if (profileCompatibility == this.profileCompatibility) {
            return this;
        }
        ContextAttribs attribs = new ContextAttribs(this);
        attribs.profileCompatibility = profileCompatibility;
        if (profileCompatibility) {
            attribs.profileCore = false;
        }
        return attribs;
    }

    public ContextAttribs withProfileES(boolean profileES) {
        if (profileES && !(majorVersion == 2 && minorVersion == 0)) {
            throw new IllegalArgumentException("The OpenGL ES profiles is only supported for OpenGL version 2.0.");
        }
        if (profileES == this.profileES) {
            return this;
        }
        ContextAttribs attribs = new ContextAttribs(this);
        attribs.profileES = profileES;
        return attribs;
    }

    public ContextAttribs withLoseContextOnReset(boolean loseContextOnReset) {
        if (loseContextOnReset == this.loseContextOnReset) {
            return this;
        }
        ContextAttribs attribs = new ContextAttribs(this);
        attribs.loseContextOnReset = loseContextOnReset;
        return attribs;
    }

    public ContextAttribs withContextResetIsolation(boolean contextResetIsolation) {
        if (contextResetIsolation == this.contextResetIsolation) {
            return this;
        }
        ContextAttribs attribs = new ContextAttribs(this);
        attribs.contextResetIsolation = contextResetIsolation;
        return attribs;
    }

    private void requireProfiles() {
        if (majorVersion < 3 || (majorVersion == 3 && minorVersion < 2)) {
            throw new IllegalArgumentException("Profiles are only supported on OpenGL version 3.2 or higher.");
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(64);
        sb.append("ContextAttribs:");
        sb.append(" Version=").append(majorVersion).append('.').append(minorVersion);
        if (0 < layerPlane) {
            sb.append(" LayerPlane=").append(layerPlane);
        }
        if (debug) {
            sb.append(" Debug");
        }
        if (forwardCompatible) {
            sb.append(" ForwardCompatible");
        }
        if (profileCore) {
            sb.append(" CoreProfile");
        } else if (profileCompatibility) {
            sb.append(" CompatibilityProfile");
        } else if (profileES) {
            sb.append(" ESProfile");
        }
        if (loseContextOnReset) {
            sb.append(" LoseContextOnReset");
        }
        if (contextResetIsolation) {
            sb.append(" ContextResetIsolation");
        }
        return sb.toString();
    }
}
