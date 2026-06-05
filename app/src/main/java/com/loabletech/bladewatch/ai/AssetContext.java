package com.overdrive.app.ai;

import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.content.res.Resources;

/**
 * Minimal Context wrapper for daemon mode.
 * Provides just enough Context for TFLite to load models from AssetManager.
 */
public class AssetContext extends ContextWrapper {
    
    private final AssetManager assetManager;
    
    public AssetContext(AssetManager assetManager) {
        super(null);
        this.assetManager = assetManager;
    }
    
    @Override
    public AssetManager getAssets() {
        return assetManager;
    }
    
    @Override
    public Resources getResources() {
        // Return null - TFLite only needs AssetManager
        return null;
    }
    
    @Override
    public String getPackageName() {
        return "com.overdrive.app";
    }
}
