/**
 * Vehicle Control — Cyberpunk VFX Engine
 * Three.js wireframe car with GSAP energy-based animations
 * State sync with BYD vehicle APIs
 *
 * Compatibility: Chrome 58+ (BYD DiLink Android 7.1 WebView)
 * - No ES modules, no import maps, no optional chaining, no nullish coalescing
 * - Uses UMD globals: THREE, THREE.GLTFLoader, THREE.OrbitControls, gsap
 */

var VC = {
    // Three.js core (initialized in init())
    scene: null,
    camera: null,
    renderer: null,
    controls: null,
    carModel: null,

    // Materials (initialized in initThreeJS())
    baseColor: null,
    wireframeMaterial: null,
    edgeLines: [],
    bodyPaintMeshes: [],

    // State
    vehicleState: {
        locked: null,
        trunkOpen: false,
        doors: { lf: 1, rf: 1, lr: 1, rr: 1, trunk: -1, hood: -1 },
        windows: { lf: 0, rf: 0, lr: 0, rr: 0 },
        soc: 0,
        rangeKm: 0,
        cloudConfigured: false,
        acOn: false,
        acTemp: 22,
        acFan: 3,
        seatHeat: { 1: 0, 2: 0 },  // 0=off, 1=low, 2=high
        seatCool: { 1: 0, 2: 0 }
    },

    pollInterval: null,
    _toastTimer: null,
    stateGlows: {},  // persistent glow lights keyed by position name
    _3dViewActive: false,
    _skySphere: null,
    _videoEl: null,
    _videoTexture: null,
    _jmuxer: null,
    _streamWs: null,

    // Color presets — realistic car paint colors
    colorPresets: [
        { name: 'Aurora White', hex: '#E8E8EC' },
        { name: 'Cosmos Black', hex: '#1A1A1E' },
        { name: 'Atlantic Blue', hex: '#1E3A5F' },
        { name: 'Deepsea Green', hex: '#1B4D3E' },
        { name: 'Burgundy Red', hex: '#6B1D2A' },
        { name: 'Storm Grey', hex: '#5C5C66' }
    ],

    // ==================== INITIALIZATION ====================

    init: function() {
        this.baseColor = new THREE.Color(0xE8E8EC); // Default: Aurora White
        this.initThreeJS();
        this.initColorPicker();
        this.loadSavedColor();
        this.loadModel();
        this.bindControls();
        this.startStateSync();
        this.checkCloudStatus();
        this.animate();
        this.init3dButton();
        this.initCloudModal();
    },

    initThreeJS: function() {
        var self = this;

        this.scene = new THREE.Scene();

        this.camera = new THREE.PerspectiveCamera(
            50, window.innerWidth / window.innerHeight, 0.1, 1000
        );
        this.camera.position.set(4, 2.5, 5);

        this.renderer = new THREE.WebGLRenderer({
            canvas: document.getElementById('vehicleCanvas'),
            antialias: true,
            alpha: true
        });
        this.renderer.setSize(window.innerWidth, window.innerHeight);
        this.renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
        this.renderer.setClearColor(0x0F0F12, 1);
        this.renderer.outputEncoding = THREE.sRGBEncoding;
        this.renderer.toneMapping = THREE.ACESFilmicToneMapping;
        this.renderer.toneMappingExposure = 1.2;

        this.controls = new THREE.OrbitControls(this.camera, this.renderer.domElement);
        this.controls.enableDamping = true;
        this.controls.dampingFactor = 0.08;
        this.controls.minDistance = 3;
        this.controls.maxDistance = 12;
        // Lock vertical rotation — keep camera above the car, no going underneath
        this.controls.minPolarAngle = Math.PI * 0.2;  // ~36° from top (don't go fully overhead)
        this.controls.maxPolarAngle = Math.PI * 0.48;  // ~86° (just above horizon, never below car)
        this.controls.enablePan = false;  // No panning — car stays centered
        this.controls.autoRotate = true;
        this.controls.autoRotateSpeed = 0.3;

        this.controls.addEventListener('start', function() {
            self.controls.autoRotate = false;
        });

        // Wireframe overlay material — glowing edges on top of the real car
        this.wireframeMaterial = new THREE.LineBasicMaterial({
            color: this.baseColor,
            linewidth: 2,
            transparent: true,
            opacity: 0.7
        });

        // Scene lighting — enhance the model's own materials
        this.addLighting();
        this.addGroundGrid();

        window.addEventListener('resize', function() { self.onResize(); });
    },

    addLighting: function() {
        // Environment lighting for PBR materials
        var ambient = new THREE.HemisphereLight(0x88aacc, 0x222244, 1.0);
        this.scene.add(ambient);

        // Key light — strong top-front
        var keyLight = new THREE.DirectionalLight(0xffffff, 1.2);
        keyLight.position.set(5, 8, 5);
        this.scene.add(keyLight);

        // Fill light
        var fillLight = new THREE.DirectionalLight(0x8899bb, 0.6);
        fillLight.position.set(-5, 4, -3);
        this.scene.add(fillLight);

        // Rim light from below — cyberpunk floor glow in selected color
        var rimLight = new THREE.PointLight(0x00E5FF, 0.6, 15);
        rimLight.position.set(0, -1.5, 0);
        this.scene.add(rimLight);
        this.rimLight = rimLight;

        // Back accent
        var backLight = new THREE.DirectionalLight(0x6644aa, 0.3);
        backLight.position.set(0, 3, -6);
        this.scene.add(backLight);
    },

    addGroundGrid: function() {
        var gridHelper = new THREE.GridHelper(20, 40, 0x1a1a2e, 0x1a1a2e);
        gridHelper.position.y = -0.01;
        gridHelper.material.opacity = 0.3;
        gridHelper.material.transparent = true;
        this.scene.add(gridHelper);
    },

    loadModel: function() {
        var self = this;
        var loader = new THREE.GLTFLoader();

        // Draco decoder — the GLB uses Draco mesh compression
        // Use the gltf/ subdirectory which has the smaller decoder optimized for glTF
        var dracoLoader = new THREE.DRACOLoader();
        dracoLoader.setDecoderPath('https://cdn.jsdelivr.net/npm/three@0.147.0/examples/js/libs/draco/gltf/');
        dracoLoader.setDecoderConfig({ type: 'js' }); // Force JS decoder (no WASM) for Chrome 58 compat
        loader.setDRACOLoader(dracoLoader);

        var loadingEl = document.getElementById('vcLoading');

        // Model path: assets/web/shared/models/byd_seal_optimized.glb
        // Extracted to /data/local/tmp/web/shared/models/byd_seal_optimized.glb by HttpServer.extractWebAssets()
        var modelPath = '../shared/models/byd_seal_optimized.glb';

        loader.load(
            modelPath,
            function(gltf) {
                self.carModel = gltf.scene;
                self.edgeLines = [];

                self.carModel.traverse(function(node) {
                    if (node.isMesh) {
                        // Identify body paint panels vs glass/chrome/rubber/interior
                        // Body paint: opaque, non-transparent, typically the largest colored surfaces
                        var mat = node.material;
                        var isBodyPaint = false;

                        if (mat && !mat.transparent && mat.opacity > 0.9) {
                            // Check if it's NOT glass (glass is usually transparent or has low opacity)
                            // Check if it's NOT black rubber/tyre (very dark, roughness ~1)
                            // Check if it's NOT chrome (metalness ~1, very light color)
                            var col = mat.color;
                            if (col) {
                                var brightness = col.r * 0.299 + col.g * 0.587 + col.b * 0.114;
                                var isVeryDark = brightness < 0.08;  // black rubber, tyres
                                var isVeryBright = brightness > 0.85; // chrome, lights
                                var isGlass = mat.transparent || (mat.opacity < 0.95);
                                var metalness = mat.metalness !== undefined ? mat.metalness : 0;

                                // Body paint: mid-range brightness, not chrome-level metalness
                                if (!isVeryDark && !isVeryBright && !isGlass && metalness < 0.95) {
                                    isBodyPaint = true;
                                }
                            }
                        }

                        if (isBodyPaint) {
                            // Store original color for reference
                            node.userData.originalColor = mat.color.clone();
                            node.userData.isBodyPaint = true;
                            // Apply the user's chosen color
                            mat.color.set(self.baseColor);
                            mat.needsUpdate = true;
                            self.bodyPaintMeshes.push(node);
                        }

                        // Keep the model's original material for everything else
                        if (mat && mat.isMeshStandardMaterial) {
                            mat.envMapIntensity = 1.0;
                            mat.needsUpdate = true;
                        }

                        // Add wireframe edge overlay
                        var edges = new THREE.EdgesGeometry(node.geometry, 20);
                        var line = new THREE.LineSegments(edges, self.wireframeMaterial);
                        node.add(line);
                        self.edgeLines.push(line);
                    }
                });

                var box = new THREE.Box3().setFromObject(self.carModel);
                var center = box.getCenter(new THREE.Vector3());
                self.carModel.position.sub(center);
                self.carModel.position.y += 0.1;

                self.scene.add(self.carModel);

                if (loadingEl) loadingEl.classList.add('hidden');
                self.triggerIdlePulse();
            },
            function(progress) {
                if (progress.total > 0) {
                    var pct = Math.round((progress.loaded / progress.total) * 100);
                    var textEl = document.querySelector('.vc-loading-text');
                    if (textEl) textEl.textContent = 'Loading model... ' + pct + '%';
                }
            },
            function(error) {
                console.error('Model load error:', error);
                var textEl = document.querySelector('.vc-loading-text');
                if (textEl) {
                    textEl.innerHTML = 'Model not found.<br>Expected <b>byd_seal_optimized.glb</b> in<br>assets/web/shared/models/';
                    textEl.style.textAlign = 'center';
                    textEl.style.lineHeight = '1.6';
                }
                // Hide spinner on error
                var spinner = document.querySelector('.vc-loading-spinner');
                if (spinner) spinner.style.display = 'none';
            }
        );
    },

    onResize: function() {
        this.camera.aspect = window.innerWidth / window.innerHeight;
        this.camera.updateProjectionMatrix();
        this.renderer.setSize(window.innerWidth, window.innerHeight);
    },

    animate: function() {
        var self = this;
        requestAnimationFrame(function() { self.animate(); });
        if (this.controls) this.controls.update();
        // Update canvas texture for WebCodecs mode (CanvasTexture doesn't auto-update like VideoTexture)
        if (this._3dViewActive && this._3dDecoderMode === 'webcodecs' && this._videoTexture) {
            this._videoTexture.needsUpdate = true;
        }
        if (this.renderer && this.scene && this.camera) {
            this.renderer.render(this.scene, this.camera);
        }
    },

    // ==================== VFX ANIMATIONS ====================

    /** Flash all body paint meshes to a color and back */
    flashBodyColor: function(flashColor, duration, repeats, callback) {
        var self = this;
        if (this.bodyPaintMeshes.length === 0) return;

        // Store current colors
        var origColors = [];
        for (var i = 0; i < this.bodyPaintMeshes.length; i++) {
            origColors.push(this.bodyPaintMeshes[i].material.color.clone());
        }

        // Flash each body mesh
        for (var j = 0; j < this.bodyPaintMeshes.length; j++) {
            gsap.to(this.bodyPaintMeshes[j].material.color, {
                r: flashColor.r, g: flashColor.g, b: flashColor.b,
                duration: duration || 0.15,
                yoyo: true,
                repeat: repeats || 1,
                ease: 'power2.out',
                onComplete: (function(idx) {
                    return function() {
                        // Restore original color
                        self.bodyPaintMeshes[idx].material.color.copy(origColors[idx]);
                        self.bodyPaintMeshes[idx].material.needsUpdate = true;
                    };
                })(j)
            });
        }

        // Also flash wireframe
        if (this.wireframeMaterial) {
            var wireOrig = this.wireframeMaterial.color.clone();
            gsap.to(this.wireframeMaterial.color, {
                r: flashColor.r, g: flashColor.g, b: flashColor.b,
                duration: duration || 0.15,
                yoyo: true,
                repeat: repeats || 1,
                ease: 'power2.out',
                onComplete: function() {
                    self.wireframeMaterial.color.copy(wireOrig);
                    if (callback) callback();
                }
            });
        }
    },

    triggerIdlePulse: function() {
        // No-op — idle pulse on wireframe was barely visible with real materials
        // The car looks good static
    },

    triggerUnlockVFX: function() {
        var self = this;
        if (!this.carModel) return;
        var white = new THREE.Color(0xFFFFFF);

        this.flashBodyColor(white, 0.12, 3, null);

        // Scale bounce
        gsap.to(this.carModel.scale, {
            x: 1.02, y: 1.02, z: 1.02,
            duration: 0.2,
            yoyo: true,
            repeat: 1,
            ease: 'power2.out'
        });
    },

    triggerLockVFX: function() {
        var self = this;
        if (!this.carModel) return;
        var red = new THREE.Color(0xFF0055);

        this.flashBodyColor(red, 0.12, 1, null);

        gsap.to(this.carModel.scale, {
            x: 0.98, y: 0.98, z: 0.98,
            duration: 0.15,
            yoyo: true,
            repeat: 1,
            ease: 'power2.out'
        });
    },

    triggerSonarVFX: function(x, y, z, color) {
        var self = this;
        if (!this.carModel) return;
        var ringColor = color || this.baseColor;

        var ringGeo = new THREE.RingGeometry(0.1, 0.15, 32);
        var ringMat = new THREE.MeshBasicMaterial({
            color: ringColor,
            side: THREE.DoubleSide,
            transparent: true,
            opacity: 1.0
        });
        var sonarRing = new THREE.Mesh(ringGeo, ringMat);
        sonarRing.position.set(x, y, z);
        sonarRing.rotation.x = Math.PI / 2;
        this.carModel.add(sonarRing);

        gsap.to(sonarRing.scale, {
            x: 6, y: 6, z: 6,
            duration: 1.2,
            ease: 'power2.out'
        });
        gsap.to(ringMat, {
            opacity: 0,
            duration: 1.2,
            ease: 'power2.out',
            onComplete: function() {
                if (self.carModel) self.carModel.remove(sonarRing);
                ringGeo.dispose();
                ringMat.dispose();
            }
        });
    },

    triggerTrunkVFX: function(opening) {
        var self = this;
        var color = opening ? this.baseColor : new THREE.Color(0xFF0055);
        this.triggerSonarVFX(0, 0.8, -2.2, color);
        if (opening) {
            setTimeout(function() { self.triggerSonarVFX(0, 0.8, -2.2, color); }, 200);
        }
    },

    triggerDoorVFX: function(door, opening) {
        var positions = {
            lf: { x: 1.0, y: 0.6, z: 0.5 },
            rf: { x: -1.0, y: 0.6, z: 0.5 },
            lr: { x: 1.0, y: 0.6, z: -0.5 },
            rr: { x: -1.0, y: 0.6, z: -0.5 }
        };
        var pos = positions[door];
        if (!pos) return;
        var color = opening ? this.baseColor : new THREE.Color(0x22C55E);
        this.triggerSonarVFX(pos.x, pos.y, pos.z, color);
    },

    triggerWindowVFX: function(area, opening) {
        var positions = {
            lf: { x: 1.0, y: 0.9, z: 0.5 },
            rf: { x: -1.0, y: 0.9, z: 0.5 },
            lr: { x: 1.0, y: 0.9, z: -0.5 },
            rr: { x: -1.0, y: 0.9, z: -0.5 }
        };
        var pos = positions[area];
        if (!pos) return;
        var color = opening ? new THREE.Color(0x38BDF8) : this.baseColor;
        this.triggerSonarVFX(pos.x, pos.y, pos.z, color);
    },

    triggerFlashVFX: function() {
        if (!this.carModel) return;
        var white = new THREE.Color(0xFFFFFF);
        this.flashBodyColor(white, 0.08, 5, null);
    },

    /** Start continuous AC sonar wave effect — semi-circular ring sweeps front to back */
    startAcSonar: function() {
        if (this._acSonarInterval) return; // already running
        var self = this;
        this._acSonarMeshes = [];

        function spawnAcRing() {
            if (!self.carModel) return;
            var ringGeo = new THREE.RingGeometry(0.1, 0.15, 32);
            var ringMat = new THREE.MeshBasicMaterial({
                color: 0x38BDF8,
                side: THREE.DoubleSide,
                transparent: true,
                opacity: 0.8
            });
            var ring = new THREE.Mesh(ringGeo, ringMat);
            ring.position.set(0, 0.5, 1.5);
            ring.rotation.x = Math.PI / 2;
            self.carModel.add(ring);
            self._acSonarMeshes.push(ring);

            // Move from z=1.5 to z=-2.0 over 1.5s while fading out
            gsap.to(ring.position, {
                z: -2.0,
                duration: 1.5,
                ease: 'linear'
            });
            gsap.to(ringMat, {
                opacity: 0,
                duration: 1.5,
                ease: 'linear',
                onComplete: function() {
                    if (self.carModel) self.carModel.remove(ring);
                    ringGeo.dispose();
                    ringMat.dispose();
                    var idx = self._acSonarMeshes.indexOf(ring);
                    if (idx !== -1) self._acSonarMeshes.splice(idx, 1);
                }
            });
        }

        spawnAcRing();
        this._acSonarInterval = setInterval(function() {
            spawnAcRing();
        }, 2000);
    },

    /** Stop continuous AC sonar effect */
    stopAcSonar: function() {
        if (this._acSonarInterval) {
            clearInterval(this._acSonarInterval);
            this._acSonarInterval = null;
        }
        if (this._acSonarMeshes && this.carModel) {
            for (var i = 0; i < this._acSonarMeshes.length; i++) {
                var mesh = this._acSonarMeshes[i];
                gsap.killTweensOf(mesh.position);
                gsap.killTweensOf(mesh.material);
                this.carModel.remove(mesh);
                mesh.geometry.dispose();
                mesh.material.dispose();
            }
        }
        this._acSonarMeshes = [];
    },

    // ==================== COLOR PICKER ====================

    initColorPicker: function() {
        var self = this;
        var container = document.getElementById('colorPicker');
        if (!container) return;

        for (var i = 0; i < this.colorPresets.length; i++) {
            (function(preset, idx) {
                var swatch = document.createElement('div');
                swatch.className = 'vc-swatch' + (idx === 0 ? ' active' : '');
                swatch.style.backgroundColor = preset.hex;
                swatch.title = preset.name;
                swatch.setAttribute('data-hex', preset.hex);
                swatch.addEventListener('click', function(e) {
                    e.stopPropagation();
                    self.setColor(preset.hex, swatch);
                });
                // Also handle touchend for WebView reliability
                swatch.addEventListener('touchend', function(e) {
                    e.preventDefault();
                    e.stopPropagation();
                    self.setColor(preset.hex, swatch);
                });
                container.appendChild(swatch);
            })(this.colorPresets[i], i);
        }

        // Custom color — use a text hex input fallback for WebView compatibility
        // (input type="color" doesn't work on Android 7.1 WebView / Chrome 58)
        var custom = document.createElement('div');
        custom.className = 'vc-swatch-custom';
        custom.title = 'Custom color';
        custom.style.position = 'relative';
        
        // Try native color picker first, fall back gracefully
        var input = document.createElement('input');
        input.type = 'color';
        input.value = '#E8E8EC';
        input.addEventListener('input', function(e) {
            self.setColor(e.target.value, null);
            custom.style.backgroundColor = e.target.value;
        });
        input.addEventListener('change', function(e) {
            self.setColor(e.target.value, null);
            custom.style.backgroundColor = e.target.value;
        });
        custom.appendChild(input);
        container.appendChild(custom);
    },

    setColor: function(hex, activeSwatch) {
        this.baseColor.set(hex);

        // Update body paint color on all identified body panels
        var newColor = new THREE.Color(hex);
        for (var i = 0; i < this.bodyPaintMeshes.length; i++) {
            var mesh = this.bodyPaintMeshes[i];
            if (mesh.material && mesh.material.color) {
                mesh.material.color.copy(newColor);
                mesh.material.needsUpdate = true;
            }
        }

        // Update wireframe to match
        if (this.wireframeMaterial) {
            gsap.killTweensOf(this.wireframeMaterial.color);
            this.wireframeMaterial.color.set(hex);
        }

        // Update rim light color
        if (this.rimLight) {
            this.rimLight.color.set(hex);
        }

        // Update active swatch
        var swatches = document.querySelectorAll('.vc-swatch');
        for (var i = 0; i < swatches.length; i++) {
            swatches[i].classList.remove('active');
        }
        if (activeSwatch) activeSwatch.classList.add('active');

        // Persist
        try { localStorage.setItem('vc_color', hex); } catch(e) {}
    },

    loadSavedColor: function() {
        var self = this;
        try {
            var saved = localStorage.getItem('vc_color');
            if (saved) {
                this.baseColor.set(saved);
                if (this.wireframeMaterial) this.wireframeMaterial.color.set(saved);
                if (this.rimLight) this.rimLight.color.set(saved);
                
                // Apply to body paint meshes if model already loaded
                if (this.bodyPaintMeshes.length > 0) {
                    var newColor = new THREE.Color(saved);
                    for (var j = 0; j < this.bodyPaintMeshes.length; j++) {
                        if (this.bodyPaintMeshes[j].material && this.bodyPaintMeshes[j].material.color) {
                            this.bodyPaintMeshes[j].material.color.copy(newColor);
                            this.bodyPaintMeshes[j].material.needsUpdate = true;
                        }
                    }
                }
                
                setTimeout(function() {
                    var swatches = document.querySelectorAll('.vc-swatch');
                    for (var i = 0; i < swatches.length; i++) {
                        swatches[i].classList.remove('active');
                        var dataHex = swatches[i].getAttribute('data-hex');
                        if (dataHex && dataHex.toLowerCase() === saved.toLowerCase()) {
                            swatches[i].classList.add('active');
                        }
                    }
                }, 100);
            }
        } catch(e) {}
    },

    // ==================== CONTROL BINDINGS ====================

    bindControls: function() {
        var self = this;

        // Lock
        this.bindBtn('btnLock', function() {
            if (!self.requireCloud()) return;
            self.setPending('btnLock', true);
            self.triggerLockVFX();
            self.apiPost('/api/vehicle/lock').then(function(result) {
                self.setPending('btnLock', false);
                if (result.success && result.commandSuccess) {
                    self.toast('Car locked', 'success');
                } else {
                    self.toast(result.error || 'Lock failed', 'error');
                }
            });
        });

        // Unlock
        this.bindBtn('btnUnlock', function() {
            if (!self.requireCloud()) return;
            self.setPending('btnUnlock', true);
            self.triggerUnlockVFX();
            self.apiPost('/api/vehicle/unlock').then(function(result) {
                self.setPending('btnUnlock', false);
                if (result.success && result.commandSuccess) {
                    self.toast('Car unlocked', 'success');
                } else {
                    self.toast(result.error || 'Unlock failed', 'error');
                }
            });
        });

        // Trunk open — shows progress: Unlocking → Opening
        this.bindBtn('btnTrunkOpen', function() {
            if (!self.requireCloud()) return;
            self.setPending('btnTrunkOpen', true);
            self.toast('Unlocking car...', 'info');
            self.triggerUnlockVFX();
            self.apiPost('/api/vehicle/trunk', { action: 'open' }).then(function(result) {
                self.setPending('btnTrunkOpen', false);
                if (result.success) {
                    self.triggerTrunkVFX(true);
                    self.toast('Trunk opening', 'success');
                } else {
                    self.toast(result.error || 'Trunk failed', 'error');
                }
            });
        });

        // Trunk close — closes trunk via local HAL + locks car
        this.bindBtn('btnTrunkClose', function() {
            self.setPending('btnTrunkClose', true);
            self.toast('Closing trunk...', 'info');
            self.triggerLockVFX();
            self.apiPost('/api/vehicle/trunk', { action: 'close' }).then(function(result) {
                self.setPending('btnTrunkClose', false);
                if (result.success) {
                    self.triggerTrunkVFX(false);
                    self.toast('Trunk closing', 'success');
                } else {
                    self.toast(result.error || 'Trunk close failed', 'error');
                }
            });
        });

        // Flash lights
        this.bindBtn('btnFlash', function() {
            if (!self.requireCloud()) return;
            self.setPending('btnFlash', true);
            self.triggerFlashVFX();
            self.apiPost('/api/vehicle/flash').then(function(result) {
                self.setPending('btnFlash', false);
                if (result.success) self.toast('Lights flashed', 'info');
                else self.toast(result.error || 'Flash failed', 'error');
            });
        });

        // Window controls (open/close per area)
        var areas = ['lf', 'rf', 'lr', 'rr'];
        for (var i = 0; i < areas.length; i++) {
            (function(area, areaNum) {
                self.bindBtn('btnWin' + area.toUpperCase() + 'Open', function() {
                    self.triggerWindowVFX(area, true);
                    self.apiPost('/api/vehicle/window', { area: areaNum, command: 1 });
                });
                self.bindBtn('btnWin' + area.toUpperCase() + 'Close', function() {
                    self.triggerWindowVFX(area, false);
                    self.apiPost('/api/vehicle/window', { area: areaNum, command: 2 });
                });
            })(areas[i], i + 1);
        }

        // All windows
        this.bindBtn('btnWinAllOpen', function() {
            for (var j = 0; j < areas.length; j++) self.triggerWindowVFX(areas[j], true);
            self.apiPost('/api/vehicle/window', { area: 0, command: 1 });
            self.toast('All windows opening', 'info');
        });
        this.bindBtn('btnWinAllClose', function() {
            for (var j = 0; j < areas.length; j++) self.triggerWindowVFX(areas[j], false);
            self.apiPost('/api/vehicle/window', { area: 0, command: 2 });
            self.toast('All windows closing', 'info');
        });

        // === CLIMATE CONTROLS ===
        this.bindBtn('btnAcOn', function() {
            // Blue burst from cabin center
            self.triggerSonarVFX(0, 0.6, 0.2, new THREE.Color(0x38BDF8));
            self.triggerSonarVFX(0, 0.6, -0.2, new THREE.Color(0x38BDF8));
            self.flashBodyColor(new THREE.Color(0x38BDF8), 0.1, 2, null);
            self.apiPost('/api/vehicle/climate', { action: 'power_on' }).then(function(r) {
                if (r.success) { self.vehicleState.acOn = true; self.updateClimateUI(); self.toast('AC On', 'success'); }
            });
        });
        this.bindBtn('btnAcOff', function() {
            self.flashBodyColor(new THREE.Color(0x71717A), 0.15, 1, null);
            self.apiPost('/api/vehicle/climate', { action: 'power_off' }).then(function(r) {
                if (r.success) { self.vehicleState.acOn = false; self.updateClimateUI(); self.toast('AC Off', 'info'); }
            });
        });
        this.bindBtn('btnTempUp', function() {
            var t = Math.min(33, self.vehicleState.acTemp + 1);
            self.vehicleState.acTemp = t;
            self.updateClimateUI();
            // Warm pulse for temp up
            self.triggerSonarVFX(0, 0.6, 0, new THREE.Color(t > 25 ? 0xFF6B35 : 0x38BDF8));
            self.apiPost('/api/vehicle/climate', { action: 'set_temp', zone: 1, temp: t });
        });
        this.bindBtn('btnTempDown', function() {
            var t = Math.max(17, self.vehicleState.acTemp - 1);
            self.vehicleState.acTemp = t;
            self.updateClimateUI();
            // Cool pulse for temp down
            self.triggerSonarVFX(0, 0.6, 0, new THREE.Color(t < 20 ? 0x38BDF8 : 0x00D4AA));
            self.apiPost('/api/vehicle/climate', { action: 'set_temp', zone: 1, temp: t });
        });
        this.bindBtn('btnFanUp', function() {
            var f = Math.min(7, self.vehicleState.acFan + 1);
            self.vehicleState.acFan = f;
            self.updateClimateUI();
            // Multiple sonar rings for higher fan — more rings = more wind
            for (var fi = 0; fi < Math.min(f, 3); fi++) {
                (function(delay) {
                    setTimeout(function() { self.triggerSonarVFX(0, 0.5, 0.3 - delay * 0.3, new THREE.Color(0x00D4AA)); }, delay * 80);
                })(fi);
            }
            self.apiPost('/api/vehicle/climate', { action: 'set_fan', fan: f });
        });
        this.bindBtn('btnFanDown', function() {
            var f = Math.max(1, self.vehicleState.acFan - 1);
            self.vehicleState.acFan = f;
            self.updateClimateUI();
            self.triggerSonarVFX(0, 0.5, 0, new THREE.Color(0x52525B));
            self.apiPost('/api/vehicle/climate', { action: 'set_fan', fan: f });
        });

        // Seat heating — cycles 0→1→2→0
        var seatPositions = {
            1: { x: 0.5, y: 0.4, z: 0.2 },   // driver
            2: { x: -0.5, y: 0.4, z: 0.2 }    // passenger
        };
        for (var si = 1; si <= 2; si++) {
            (function(pos) {
                self.bindBtn('btnSeatHeat' + pos, function() {
                    var cur = self.vehicleState.seatHeat[pos] || 0;
                    var next = (cur + 1) % 3;
                    self.vehicleState.seatHeat[pos] = next;
                    self.vehicleState.seatCool[pos] = 0;
                    self.updateSeatUI(pos);
                    self.updateSeatGlows();
                    // Heat VFX — warm sonar at seat, intensity scales with level
                    var sp = seatPositions[pos];
                    if (next > 0) {
                        var heatColor = next === 2 ? 0xFF4500 : 0xFF8C00;
                        self.triggerSonarVFX(sp.x, sp.y, sp.z, new THREE.Color(heatColor));
                        if (next === 2) {
                            setTimeout(function() { self.triggerSonarVFX(sp.x, sp.y + 0.2, sp.z, new THREE.Color(0xFF4500)); }, 120);
                        }
                        self.toast('Seat heat: ' + (next === 1 ? 'Low' : 'High'), 'success');
                    } else {
                        self.toast('Seat heat: Off', 'info');
                    }
                    self.apiPost('/api/vehicle/seat', { action: 'heating', position: pos, level: next });
                });
                self.bindBtn('btnSeatCool' + pos, function() {
                    var cur = self.vehicleState.seatCool[pos] || 0;
                    var next = (cur + 1) % 3;
                    self.vehicleState.seatCool[pos] = next;
                    self.vehicleState.seatHeat[pos] = 0;
                    self.updateSeatUI(pos);
                    self.updateSeatGlows();
                    // Cool VFX — blue sonar at seat
                    var sp = seatPositions[pos];
                    if (next > 0) {
                        var coolColor = next === 2 ? 0x00BFFF : 0x87CEEB;
                        self.triggerSonarVFX(sp.x, sp.y, sp.z, new THREE.Color(coolColor));
                        if (next === 2) {
                            setTimeout(function() { self.triggerSonarVFX(sp.x, sp.y + 0.2, sp.z, new THREE.Color(0x00BFFF)); }, 120);
                        }
                        self.toast('Seat cool: ' + (next === 1 ? 'Low' : 'High'), 'success');
                    } else {
                        self.toast('Seat cool: Off', 'info');
                    }
                    self.apiPost('/api/vehicle/seat', { action: 'ventilation', position: pos, level: next });
                });
            })(si);
        }
    },

    bindBtn: function(id, handler) {
        var el = document.getElementById(id);
        if (el) el.addEventListener('click', handler);
    },

    setPending: function(id, pending) {
        var el = document.getElementById(id);
        if (!el) return;
        if (pending) {
            el.classList.add('pending');
        } else {
            el.classList.remove('pending');
        }
    },

    // ==================== STATE SYNC ====================

    startStateSync: function() {
        var self = this;
        this.fetchState();
        this.pollInterval = setInterval(function() { self.fetchState(); }, 3000);
    },

    fetchState: function() {
        var self = this;
        fetch('/api/vehicle/state').then(function(resp) {
            return resp.json();
        }).then(function(data) {
            if (!data.success) return;

            var wasLocked = self.vehicleState.locked;

            // Doors (lock status: 1=locked, 2=unlocked)
            if (data.doors) {
                var d = data.doors;
                self.vehicleState.doors = {
                    lf: d.lf || -1, rf: d.rf || -1,
                    lr: d.lr || -1, rr: d.rr || -1,
                    trunk: d.trunk || -1, hood: d.hood || -1
                };
                var overall = (d.overall !== undefined && d.overall !== null) ? d.overall : -1;
                if (overall === 1) {
                    self.vehicleState.locked = true;
                } else if (overall === 2) {
                    self.vehicleState.locked = false;
                } else {
                    self.vehicleState.locked = null; // unknown
                }
            }

            // Windows
            if (data.windows) {
                var w = data.windows;
                self.vehicleState.windows = {
                    lf: w.lf >= 0 ? w.lf : 0,
                    rf: w.rf >= 0 ? w.rf : 0,
                    lr: w.lr >= 0 ? w.lr : 0,
                    rr: w.rr >= 0 ? w.rr : 0
                };
            }

            // Battery
            if (data.battery) {
                self.vehicleState.soc = data.battery.soc || 0;
                self.vehicleState.rangeKm = data.battery.rangeKm || data.battery.bodyworkRangeKm || 0;
            }

            // Climate
            if (data.climate) {
                if (data.climate.acOn !== undefined) self.vehicleState.acOn = data.climate.acOn;
                if (data.climate.insideTempC !== undefined && data.climate.insideTempC > 0) {
                    // Use inside temp as display reference (actual set temp not available from state)
                }
            }

            // Update UI
            self.updateHUD();
            self.updateWindowBars();
            self.updateDoorIndicators();
            self.updateTrunkIndicator();
            self.updateWindowGlows();
            self.updateClimateUI();
            self.updateSeatGlows();

        }).catch(function(e) {
            console.warn('[VC] State fetch error:', e);
        });
    },

    checkCloudStatus: function() {
        var self = this;
        fetch('/api/vehicle/cloud-status').then(function(resp) {
            return resp.json();
        }).then(function(data) {
            self.vehicleState.cloudConfigured = data.configured && data.verified;
            self.updateCloudIndicator();
        }).catch(function(e) {
            console.warn('[VC] Cloud status error:', e);
        });
    },

    // ==================== CLOUD MODAL ====================

    initCloudModal: function() {
        var self = this;
        var dismissBtn = document.getElementById('cloudModalDismiss');
        if (dismissBtn) {
            dismissBtn.addEventListener('click', function() { self.hideCloudModal(); });
        }
        // Also dismiss on overlay click (outside the modal card)
        var overlay = document.getElementById('cloudModal');
        if (overlay) {
            overlay.addEventListener('click', function(e) {
                if (e.target === overlay) self.hideCloudModal();
            });
        }
    },

    /**
     * Guard for cloud-requiring actions.
     * Returns true if cloud is configured (action can proceed).
     * Returns false and shows modal if cloud is not configured.
     */
    requireCloud: function() {
        if (this.vehicleState.cloudConfigured) return true;
        this.showCloudModal();
        return false;
    },

    showCloudModal: function() {
        var overlay = document.getElementById('cloudModal');
        if (overlay) overlay.classList.add('visible');
    },

    hideCloudModal: function() {
        var overlay = document.getElementById('cloudModal');
        if (overlay) overlay.classList.remove('visible');
    },

    // ==================== UI UPDATES ====================

    updateHUD: function() {
        var socEl = document.getElementById('socValue');
        if (socEl) socEl.textContent = Math.round(this.vehicleState.soc) + '%';

        var socFill = document.getElementById('socFill');
        if (socFill) socFill.style.width = Math.min(100, Math.max(0, this.vehicleState.soc)) + '%';

        var rangeEl = document.getElementById('rangeValue');
        if (rangeEl) rangeEl.textContent = Math.round(this.vehicleState.rangeKm) + ' km';

        this.updateLockUI(this.vehicleState.locked);
    },

    updateLockUI: function(locked) {
        var lockBtn = document.getElementById('btnLock');
        var unlockBtn = document.getElementById('btnUnlock');
        var lockStatus = document.getElementById('lockStatus');

        // locked can be true, false, or null (unknown)
        if (lockBtn) { if (locked === true) lockBtn.classList.add('on'); else lockBtn.classList.remove('on'); }
        if (unlockBtn) { if (locked === false) unlockBtn.classList.add('on'); else unlockBtn.classList.remove('on'); }
        if (lockStatus) {
            lockStatus.textContent = locked === true ? 'Locked' : (locked === false ? 'Unlocked' : 'Unknown');
            var dot = lockStatus.previousElementSibling;
            if (dot) {
                dot.className = 'dot ' + (locked === true ? 'green' : (locked === false ? 'amber' : 'grey'));
            }
        }
    },

    updateWindowBars: function() {
        var areas = ['lf', 'rf', 'lr', 'rr'];
        for (var i = 0; i < areas.length; i++) {
            var area = areas[i];
            var fill = document.getElementById('winFill_' + area);
            var pct = document.getElementById('winPct_' + area);
            var val = this.vehicleState.windows[area] || 0;
            if (fill) fill.style.width = val + '%';
            if (pct) pct.textContent = val + '%';
        }
    },

    updateDoorIndicators: function() {
        var areas = ['lf', 'rf', 'lr', 'rr'];
        for (var i = 0; i < areas.length; i++) {
            var area = areas[i];
            var el = document.getElementById('doorState_' + area);
            if (!el) continue;
            var val = this.vehicleState.doors[area];
            if (val === 1) {
                el.textContent = '\uD83D\uDD12'; // locked
                el.title = 'Locked';
                this.removeStateGlow('door_' + area);
            } else if (val === 2) {
                el.textContent = '\uD83D\uDD13'; // unlocked
                el.title = 'Unlocked';
                this.setStateGlow('door_' + area, this.getDoorPosition(area), 0xF59E0B); // amber
            } else {
                el.textContent = '\u2014';
                el.title = 'Unknown';
                this.removeStateGlow('door_' + area);
            }
        }
    },

    /** Update persistent glow for trunk open state */
    updateTrunkIndicator: function() {
        // doorLockStatus[4] = trunk: 1=locked/closed, 2=unlocked/open
        var trunkVal = -1;
        if (this.vehicleState.doors && this.vehicleState.doors.trunk !== undefined) {
            trunkVal = this.vehicleState.doors.trunk;
        }
        if (trunkVal === 2) {
            this.setStateGlow('trunk', { x: 0, y: 0.8, z: -2.2 }, 0x00D4AA); // green glow
        } else {
            this.removeStateGlow('trunk');
        }
    },

    /** Update persistent glow for open windows */
    updateWindowGlows: function() {
        var areas = ['lf', 'rf', 'lr', 'rr'];
        for (var i = 0; i < areas.length; i++) {
            var area = areas[i];
            var pct = this.vehicleState.windows[area] || 0;
            if (pct > 10) {
                this.setStateGlow('win_' + area, this.getWindowPosition(area), 0x38BDF8); // blue
            } else {
                this.removeStateGlow('win_' + area);
            }
        }
    },

    getDoorPosition: function(area) {
        var positions = {
            lf: { x: 1.0, y: 0.6, z: 0.5 },
            rf: { x: -1.0, y: 0.6, z: 0.5 },
            lr: { x: 1.0, y: 0.6, z: -0.5 },
            rr: { x: -1.0, y: 0.6, z: -0.5 }
        };
        return positions[area] || { x: 0, y: 0.5, z: 0 };
    },

    getWindowPosition: function(area) {
        var positions = {
            lf: { x: 1.0, y: 0.9, z: 0.5 },
            rf: { x: -1.0, y: 0.9, z: 0.5 },
            lr: { x: 1.0, y: 0.9, z: -0.5 },
            rr: { x: -1.0, y: 0.9, z: -0.5 }
        };
        return positions[area] || { x: 0, y: 0.9, z: 0 };
    },

    /** Add or update a persistent glow indicator on the car */
    setStateGlow: function(key, pos, colorHex) {
        if (!this.carModel) return;
        this.removeStateGlow(key);

        // Glowing ring — much more visible than a point light
        var ringGeo = new THREE.RingGeometry(0.08, 0.14, 24);
        var ringMat = new THREE.MeshBasicMaterial({
            color: colorHex, side: THREE.DoubleSide,
            transparent: true, opacity: 0.85
        });
        var ring = new THREE.Mesh(ringGeo, ringMat);
        ring.position.set(pos.x, pos.y, pos.z);
        ring.rotation.x = Math.PI / 2;
        this.carModel.add(ring);

        // Point light for ambient glow on nearby surfaces
        var light = new THREE.PointLight(colorHex, 0.6, 2.5);
        light.position.set(pos.x, pos.y, pos.z);
        this.carModel.add(light);

        // Pulse animation on the ring
        gsap.to(ringMat, {
            opacity: 0.3, duration: 1,
            yoyo: true, repeat: -1, ease: 'sine.inOut'
        });

        this.stateGlows[key] = { ring: ring, light: light, geo: ringGeo, mat: ringMat };
    },

    /** Remove a persistent glow */
    removeStateGlow: function(key) {
        var glow = this.stateGlows[key];
        if (!glow) return;
        gsap.killTweensOf(glow.mat);
        if (this.carModel) {
            this.carModel.remove(glow.ring);
            this.carModel.remove(glow.light);
        }
        glow.geo.dispose();
        glow.mat.dispose();
        delete this.stateGlows[key];
    },

    updateCloudIndicator: function() {
        var textEl = document.getElementById('cloudStatusText');
        var pillEl = document.getElementById('cloudStatus');
        if (!pillEl) return;
        var dot = pillEl.querySelector('.dot');
        if (this.vehicleState.cloudConfigured) {
            if (dot) dot.className = 'dot green';
            if (textEl) textEl.textContent = 'Cloud Connected';
        } else {
            if (dot) dot.className = 'dot red';
            if (textEl) textEl.textContent = 'Cloud Not Configured';
        }
    },

    updateClimateUI: function() {
        var tempEl = document.getElementById('acTemp');
        if (tempEl) tempEl.textContent = this.vehicleState.acTemp + '\u00B0';

        var fanEl = document.getElementById('acFan');
        if (fanEl) fanEl.textContent = this.vehicleState.acFan;

        var btnOn = document.getElementById('btnAcOn');
        if (btnOn) { if (this.vehicleState.acOn) btnOn.classList.add('on'); else btnOn.classList.remove('on'); }

        if (this.vehicleState.acOn) {
            this.setStateGlow('ac', { x: 0, y: 0.5, z: 0.3 }, 0x38BDF8);
            this.startAcSonar();
        } else {
            this.removeStateGlow('ac');
            this.stopAcSonar();
        }
    },

    updateSeatUI: function(pos) {
        var heatBtn = document.getElementById('btnSeatHeat' + pos);
        var coolBtn = document.getElementById('btnSeatCool' + pos);
        var heatLvl = this.vehicleState.seatHeat[pos] || 0;
        var coolLvl = this.vehicleState.seatCool[pos] || 0;

        if (heatBtn) { if (heatLvl > 0) heatBtn.classList.add('on'); else heatBtn.classList.remove('on'); }
        if (coolBtn) { if (coolLvl > 0) coolBtn.classList.add('on'); else coolBtn.classList.remove('on'); }
    },

    updateSeatGlows: function() {
        var self = this;
        if (!this._seatSonarIntervals) this._seatSonarIntervals = {};
        if (!this._seatSonarMeshes) this._seatSonarMeshes = {};

        // Seat positions on the 3D model (approximate interior positions)
        var seatPositions = {
            1: { x: 0.5, y: 0.4, z: 0.2 },   // driver
            2: { x: -0.5, y: 0.4, z: 0.2 }    // passenger
        };

        for (var pos = 1; pos <= 2; pos++) {
            var heatLvl = this.vehicleState.seatHeat[pos] || 0;
            var coolLvl = this.vehicleState.seatCool[pos] || 0;
            var key = 'seat_' + pos;

            if (heatLvl > 0 || coolLvl > 0) {
                // Determine color
                var colorHex;
                if (heatLvl > 0) {
                    colorHex = heatLvl === 2 ? 0xFF4500 : 0xFF8C00;
                } else {
                    colorHex = coolLvl === 2 ? 0x00BFFF : 0x87CEEB;
                }

                // If already running with same color, skip
                if (this._seatSonarIntervals[key] && this._seatSonarIntervals[key].color === colorHex) continue;

                // Clear existing interval for this seat if any
                this._stopSeatSonar(key);

                var sp = seatPositions[pos];
                (function(seatKey, seatPos, seatColor) {
                    if (!self._seatSonarMeshes[seatKey]) self._seatSonarMeshes[seatKey] = [];

                    function spawnSeatRing() {
                        if (!self.carModel) return;
                        var ringGeo = new THREE.RingGeometry(0.08, 0.12, 24);
                        var ringMat = new THREE.MeshBasicMaterial({
                            color: seatColor,
                            side: THREE.DoubleSide,
                            transparent: true,
                            opacity: 0.8
                        });
                        var ring = new THREE.Mesh(ringGeo, ringMat);
                        ring.position.set(seatPos.x, seatPos.y, seatPos.z);
                        ring.rotation.x = Math.PI / 2;
                        self.carModel.add(ring);
                        self._seatSonarMeshes[seatKey].push(ring);

                        // Expand from scale 1 to 4 and fade out over 1 second
                        gsap.to(ring.scale, {
                            x: 4, y: 4, z: 4,
                            duration: 1,
                            ease: 'power2.out'
                        });
                        gsap.to(ringMat, {
                            opacity: 0,
                            duration: 1,
                            ease: 'power2.out',
                            onComplete: function() {
                                if (self.carModel) self.carModel.remove(ring);
                                ringGeo.dispose();
                                ringMat.dispose();
                                var meshes = self._seatSonarMeshes[seatKey];
                                if (meshes) {
                                    var idx = meshes.indexOf(ring);
                                    if (idx !== -1) meshes.splice(idx, 1);
                                }
                            }
                        });
                    }

                    spawnSeatRing();
                    var intervalId = setInterval(function() {
                        spawnSeatRing();
                    }, 1500);

                    self._seatSonarIntervals[seatKey] = { id: intervalId, color: seatColor };
                })(key, sp, colorHex);
            } else {
                // Seat is off — stop sonar
                this._stopSeatSonar(key);
            }
        }
    },

    /** Stop sonar for a specific seat and clean up meshes */
    _stopSeatSonar: function(key) {
        if (this._seatSonarIntervals && this._seatSonarIntervals[key]) {
            clearInterval(this._seatSonarIntervals[key].id);
            delete this._seatSonarIntervals[key];
        }
        if (this._seatSonarMeshes && this._seatSonarMeshes[key]) {
            var meshes = this._seatSonarMeshes[key];
            for (var i = 0; i < meshes.length; i++) {
                var mesh = meshes[i];
                gsap.killTweensOf(mesh.scale);
                gsap.killTweensOf(mesh.material);
                if (this.carModel) this.carModel.remove(mesh);
                mesh.geometry.dispose();
                mesh.material.dispose();
            }
            this._seatSonarMeshes[key] = [];
        }
        // Also remove the static glow
        this.removeStateGlow(key);
    },

    // ==================== 3D SURROUND VIEW ====================

    init3dButton: function() {
        var self = this;
        // Hide button if running inside app WebView (AndroidBridge is injected by WebViewFragment)
        if (window.AndroidBridge) {
            var btn = document.getElementById('btn3dView');
            if (btn) btn.style.display = 'none';
            return;
        }
        this.bindBtn('btn3dView', function() {
            if (self._3dViewActive) {
                self.stop3dView();
            } else {
                self.start3dView();
            }
        });
    },

    start3dView: function() {
        var self = this;
        this._3dViewActive = true;
        this._3dDecoderMode = null;  // 'webcodecs' or 'jmuxer'
        var btn = document.getElementById('btn3dView');
        if (btn) btn.classList.add('on');

        try {
            // Decoder selection: WebCodecs (iOS/modern) → JMuxer (Android/Chrome with MSE)
            var hasWebCodecs = ('VideoDecoder' in window) && ('EncodedVideoChunk' in window) && (typeof SotaPlayer !== 'undefined');
            var hasJMuxer = (typeof JMuxer !== 'undefined') && (!!window.MediaSource);

            if (hasWebCodecs) {
                // WebCodecs path — renders to canvas, use CanvasTexture for Three.js
                this._3dDecoderMode = 'webcodecs';
                this._3dCanvas = document.createElement('canvas');
                this._3dCanvas.width = 1280;
                this._3dCanvas.height = 960;
                this._3dCanvas.style.display = 'none';
                document.body.appendChild(this._3dCanvas);

                var protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
                var wsUrl = protocol + '//' + window.location.host + '/ws';

                this._sotaPlayer = new SotaPlayer(this._3dCanvas, wsUrl);
                this._sotaPlayer.onConnected = function() {
                    console.log('[VC] 3D WebCodecs stream connected');
                };
                this._sotaPlayer.onDisconnected = function() {
                    console.log('[VC] 3D WebCodecs stream disconnected');
                    if (self._3dViewActive && self._sotaPlayer) {
                        // SotaPlayer auto-reconnects internally
                    }
                };
                this._sotaPlayer.onError = function(e) {
                    console.error('[VC] 3D WebCodecs error:', e);
                };
                this._sotaPlayer.start();

                // Create sky sphere with canvas texture
                this._createSkySphere();

            } else if (hasJMuxer) {
                // JMuxer path — renders to video element, use VideoTexture for Three.js
                this._3dDecoderMode = 'jmuxer';
                this._videoEl = document.createElement('video');
                this._videoEl.id = 'vc3d_stream_video';
                this._videoEl.setAttribute('autoplay', '');
                this._videoEl.setAttribute('muted', '');
                this._videoEl.setAttribute('playsinline', '');
                this._videoEl.style.display = 'none';
                document.body.appendChild(this._videoEl);

                this._jmuxer = new JMuxer({
                    node: 'vc3d_stream_video',
                    mode: 'video',
                    fps: 15,
                    flushingTime: 0,
                    debug: false,
                    onReady: function() {
                        console.log('[VC] JMuxer ready for 3D view');
                        self._videoEl.play().catch(function(e) {
                            console.warn('[VC] Autoplay blocked:', e.message);
                        });
                    },
                    onError: function(e) {
                        console.error('[VC] JMuxer error:', e);
                    }
                });

                // Connect WebSocket and feed JMuxer
                this._connectStream();

                // Create sky sphere with video texture
                this._createSkySphere();

            } else {
                console.error('[VC] No H.264 decoder available (need WebCodecs or MediaSource)');
                this.toast('No video decoder available', 'error');
                this._3dViewActive = false;
                if (btn) btn.classList.remove('on');
                return;
            }
        } catch(e) {
            console.error('[VC] 3D view start error:', e);
            this.toast('3D view failed: ' + e.message, 'error');
        }

        this.toast('3D Surround View active', 'info');
    },

    /**
     * Connect (or reconnect) the WebSocket stream for 3D surround view (JMuxer mode only).
     * Uses the same /ws endpoint as the live view stream.
     */
    _connectStream: function() {
        var self = this;
        if (!this._3dViewActive || this._3dDecoderMode !== 'jmuxer') return;

        // Close existing connection if any
        if (this._streamWs) {
            this._streamWs.onclose = null;
            this._streamWs.onerror = null;
            this._streamWs.close();
            this._streamWs = null;
        }

        var protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        var wsUrl = protocol + '//' + window.location.host + '/ws';
        console.log('[VC] 3D stream connecting:', wsUrl);

        try {
            this._streamWs = new WebSocket(wsUrl);
            this._streamWs.binaryType = 'arraybuffer';

            this._streamWs.onopen = function() {
                console.log('[VC] 3D stream connected');
            };

            this._streamWs.onmessage = function(evt) {
                if (self._jmuxer && evt.data instanceof ArrayBuffer) {
                    self._jmuxer.feed({ video: new Uint8Array(evt.data) });
                }
            };

            this._streamWs.onerror = function(e) {
                console.warn('[VC] 3D stream WebSocket error:', e);
            };

            this._streamWs.onclose = function() {
                console.log('[VC] 3D stream disconnected');
                if (self._3dViewActive) {
                    setTimeout(function() {
                        if (self._3dViewActive) {
                            self._connectStream();
                        }
                    }, 3000);
                }
            };
        } catch(e) {
            console.error('[VC] WebSocket connection failed:', e);
            if (this._3dViewActive) {
                setTimeout(function() {
                    if (self._3dViewActive) self._connectStream();
                }, 3000);
            }
        }
    },

    stop3dView: function() {
        this._3dViewActive = false;
        var btn = document.getElementById('btn3dView');
        if (btn) btn.classList.remove('on');

        // Stop WebCodecs (SotaPlayer) if active
        if (this._sotaPlayer) {
            this._sotaPlayer.stop();
            this._sotaPlayer = null;
        }

        // Remove WebCodecs canvas
        if (this._3dCanvas) {
            if (this._3dCanvas.parentNode) this._3dCanvas.parentNode.removeChild(this._3dCanvas);
            this._3dCanvas = null;
        }

        // Close JMuxer WebSocket (disable reconnect first)
        if (this._streamWs) {
            this._streamWs.onclose = null;
            this._streamWs.onerror = null;
            this._streamWs.close();
            this._streamWs = null;
        }

        // Destroy JMuxer
        if (this._jmuxer) {
            try { this._jmuxer.destroy(); } catch(e) {}
            this._jmuxer = null;
        }

        // Remove video element
        if (this._videoEl) {
            this._videoEl.pause();
            this._videoEl.src = '';
            if (this._videoEl.parentNode) this._videoEl.parentNode.removeChild(this._videoEl);
            this._videoEl = null;
        }

        // Remove sky sphere
        if (this._skySphere && this.scene) {
            this.scene.remove(this._skySphere);
            this._skySphere.geometry.dispose();
            this._skySphere.material.dispose();
            this._skySphere = null;
        }

        if (this._videoTexture) {
            this._videoTexture.dispose();
            this._videoTexture = null;
        }

        this._3dDecoderMode = null;
        this.toast('3D View off', 'info');
    },

    _createSkySphere: function() {
        if (!this.scene) return;

        // Choose texture source based on active decoder mode
        if (this._3dDecoderMode === 'webcodecs' && this._3dCanvas) {
            // WebCodecs renders to canvas — use CanvasTexture (must be updated each frame)
            this._videoTexture = new THREE.CanvasTexture(this._3dCanvas);
            this._videoTexture.minFilter = THREE.LinearFilter;
            this._videoTexture.magFilter = THREE.LinearFilter;
        } else if (this._3dDecoderMode === 'jmuxer' && this._videoEl) {
            // JMuxer renders to video element — use VideoTexture (auto-updates)
            this._videoTexture = new THREE.VideoTexture(this._videoEl);
            this._videoTexture.minFilter = THREE.LinearFilter;
            this._videoTexture.magFilter = THREE.LinearFilter;
        } else {
            console.error('[VC] No texture source available for sky sphere');
            return;
        }

        // Custom shader material for fisheye undistortion
        // The mosaic is 2x2 (front=TL, right=TR, rear=BL, left=BR)
        // Each quadrant is a fisheye image covering ~190° FOV
        var vertexShader = [
            'varying vec3 vWorldDir;',
            'void main() {',
            '    vec4 worldPos = modelMatrix * vec4(position, 1.0);',
            '    vWorldDir = normalize(worldPos.xyz);',
            '    gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);',
            '}'
        ].join('\n');

        var fragmentShader = [
            'uniform sampler2D mosaic;',
            'varying vec3 vWorldDir;',
            '',
            'const float PI = 3.14159265359;',
            'const float FOV = 3.3; // ~190 degrees in radians',
            '',
            '// Fisheye equidistant projection: pixel radius = f * theta',
            'vec2 fisheyeProject(vec3 dir, float fov) {',
            '    float theta = acos(clamp(dir.z, -1.0, 1.0));',
            '    if (theta > fov * 0.5) return vec2(-1.0);',
            '    float phi = atan(dir.y, dir.x);',
            '    float r = theta / (fov * 0.5);',
            '    return vec2(0.5 + r * cos(phi) * 0.5, 0.5 + r * sin(phi) * 0.5);',
            '}',
            '',
            'void main() {',
            '    vec3 dir = normalize(vWorldDir);',
            '    vec2 uv = vec2(-1.0);',
            '    vec2 offset = vec2(0.0);',
            '',
            '    // Determine which camera sees this direction',
            '    // Front camera: +Z direction',
            '    vec3 frontDir = vec3(dir.x, dir.y, dir.z);',
            '    vec2 frontUV = fisheyeProject(frontDir, FOV);',
            '    if (frontUV.x >= 0.0 && dir.z > 0.0) {',
            '        uv = frontUV * 0.5; // top-left quadrant',
            '    }',
            '',
            '    // Right camera: +X direction',
            '    vec3 rightDir = vec3(-dir.z, dir.y, dir.x);',
            '    vec2 rightUV = fisheyeProject(rightDir, FOV);',
            '    if (rightUV.x >= 0.0 && dir.x > 0.0 && uv.x < 0.0) {',
            '        uv = rightUV * 0.5 + vec2(0.5, 0.0); // top-right quadrant',
            '    }',
            '',
            '    // Rear camera: -Z direction',
            '    vec3 rearDir = vec3(-dir.x, dir.y, -dir.z);',
            '    vec2 rearUV = fisheyeProject(rearDir, FOV);',
            '    if (rearUV.x >= 0.0 && dir.z < 0.0 && uv.x < 0.0) {',
            '        uv = rearUV * 0.5 + vec2(0.0, 0.5); // bottom-left quadrant',
            '    }',
            '',
            '    // Left camera: -X direction',
            '    vec3 leftDir = vec3(dir.z, dir.y, -dir.x);',
            '    vec2 leftUV = fisheyeProject(leftDir, FOV);',
            '    if (leftUV.x >= 0.0 && dir.x < 0.0 && uv.x < 0.0) {',
            '        uv = leftUV * 0.5 + vec2(0.5, 0.5); // bottom-right quadrant',
            '    }',
            '',
            '    if (uv.x < 0.0) {',
            '        gl_FragColor = vec4(0.06, 0.06, 0.07, 1.0); // dark fill for blind spots',
            '    } else {',
            '        gl_FragColor = texture2D(mosaic, uv);',
            '    }',
            '}'
        ].join('\n');

        var sphereGeo = new THREE.SphereGeometry(40, 64, 32);
        // Flip normals inward so we see the inside
        sphereGeo.scale(-1, 1, 1);

        var sphereMat = new THREE.ShaderMaterial({
            uniforms: {
                mosaic: { value: this._videoTexture }
            },
            vertexShader: vertexShader,
            fragmentShader: fragmentShader,
            side: THREE.BackSide
        });

        this._skySphere = new THREE.Mesh(sphereGeo, sphereMat);
        this.scene.add(this._skySphere);
    },

    // ==================== API HELPERS ====================

    apiPost: function(url, body) {
        return fetch(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: body ? JSON.stringify(body) : '{}'
        }).then(function(resp) {
            return resp.json();
        }).catch(function(e) {
            return { success: false, error: 'Network error: ' + e.message };
        });
    },

    toast: function(message, type) {
        var el = document.getElementById('vcToast');
        if (!el) return;
        el.textContent = message;
        el.className = 'vc-toast show ' + (type || 'info');
        clearTimeout(this._toastTimer);
        var toastEl = el;
        this._toastTimer = setTimeout(function() {
            toastEl.classList.remove('show');
        }, 2500);
    }
};

// Boot when DOM is ready
document.addEventListener('DOMContentLoaded', function() { VC.init(); });
