/**
 * Platform shim for Goose Android
 * 
 * This script is injected before the goose2 frontend loads.
 * It replaces Tauri-specific APIs with Android WebView equivalents.
 * 
 * The goose2 frontend uses:
 *   1. invoke("get_goose_serve_url") → returns WebSocket URL
 *   2. WebSocket connection to goose serve
 *   3. Standard web APIs for everything else
 * 
 * This shim provides the Tauri invoke() replacement so the frontend
 * can discover the goose server without modification.
 */

(function() {
    'use strict';

    // =========================================================================
    // Tauri API Shim
    // =========================================================================

    // The goose2 frontend imports { invoke } from "@tauri-apps/api/core"
    // We intercept this by providing a global __TAURI_INTERNALS__ object
    // that Tauri's JS runtime checks for.

    window.__TAURI_INTERNALS__ = {
        invoke: function(cmd, args) {
            return handleInvoke(cmd, args);
        },
        transformCallback: function(callback) {
            const id = Math.random().toString(36).substring(2);
            window[`_${id}`] = callback;
            return id;
        }
    };

    // Also provide the module-level invoke for direct imports
    window.__TAURI__ = {
        core: {
            invoke: function(cmd, args) {
                return handleInvoke(cmd, args);
            }
        },
        event: {
            listen: function(event, handler) {
                // Register event listener
                if (!window.__gooseEventListeners) {
                    window.__gooseEventListeners = {};
                }
                if (!window.__gooseEventListeners[event]) {
                    window.__gooseEventListeners[event] = [];
                }
                window.__gooseEventListeners[event].push(handler);
                return Promise.resolve(function unlisten() {
                    const listeners = window.__gooseEventListeners[event];
                    if (listeners) {
                        const idx = listeners.indexOf(handler);
                        if (idx >= 0) listeners.splice(idx, 1);
                    }
                });
            },
            emit: function(event, payload) {
                return Promise.resolve();
            }
        },
        window: {
            getCurrentWindow: function() {
                return {
                    setTitle: function() { return Promise.resolve(); },
                    show: function() { return Promise.resolve(); },
                    hide: function() { return Promise.resolve(); },
                    close: function() { return Promise.resolve(); },
                    minimize: function() { return Promise.resolve(); },
                    maximize: function() { return Promise.resolve(); },
                    isMaximized: function() { return Promise.resolve(false); },
                    isMinimized: function() { return Promise.resolve(false); },
                    onCloseRequested: function() { return Promise.resolve(function() {}); },
                };
            }
        }
    };

    /**
     * Handle Tauri invoke() calls by routing to Android equivalents.
     */
    function handleInvoke(cmd, args) {
        switch (cmd) {
            case 'get_goose_serve_url':
                // Primary connection point - return the WebSocket URL
                var port = window.GOOSE_PORT || 3284;
                return Promise.resolve('ws://127.0.0.1:' + port + '/acp');

            case 'get_home_dir':
                if (window.AndroidBridge) {
                    return Promise.resolve(window.AndroidBridge.getHomeDir());
                }
                return Promise.resolve('/data/data/io.github.gooseandroid/files/home');

            case 'save_exported_session_file':
                // Use Android share intent instead of file save dialog
                if (window.AndroidBridge && args && args.content) {
                    window.AndroidBridge.shareText(
                        args.filename || 'goose-session.json',
                        args.content
                    );
                }
                return Promise.resolve({ success: true });

            case 'path_exists':
                // Can't easily check from WebView, assume false
                return Promise.resolve(false);

            case 'list_directory_entries':
                return Promise.resolve([]);

            case 'resolve_path':
                return Promise.resolve(args && args.path ? args.path : '');

            case 'inspect_attachment_paths':
                return Promise.resolve([]);

            case 'list_files_for_mentions':
                return Promise.resolve([]);

            case 'read_image_attachment':
                return Promise.resolve(null);

            // Persona/agent commands
            case 'list_personas':
                return Promise.resolve([]);

            case 'create_persona':
            case 'update_persona':
            case 'delete_persona':
            case 'refresh_personas':
                return Promise.resolve(null);

            // Project commands
            case 'list_projects':
            case 'list_archived_projects':
                return Promise.resolve([]);

            case 'create_project':
            case 'update_project':
            case 'delete_project':
            case 'get_project':
            case 'reorder_projects':
            case 'archive_project':
            case 'restore_project':
                return Promise.resolve(null);

            // Git commands (not available on Android without dev-pack)
            case 'get_git_state':
                return Promise.resolve({ initialized: false, branch: null });

            case 'get_changed_files':
                return Promise.resolve([]);

            case 'git_switch_branch':
            case 'git_stash':
            case 'git_init':
            case 'git_fetch':
            case 'git_pull':
            case 'git_create_branch':
            case 'git_create_worktree':
                return Promise.resolve(null);

            // Model/provider setup
            case 'authenticate_model_provider':
                return Promise.resolve({ success: true });

            case 'check_agent_installed':
                return Promise.resolve(true);

            case 'check_agent_auth':
                return Promise.resolve({ authenticated: true });

            case 'install_agent':
            case 'authenticate_agent':
                return Promise.resolve(null);

            // Doctor commands
            case 'run_doctor':
            case 'run_doctor_fix':
                return Promise.resolve({ checks: [], allPassed: true });

            // Project icons
            case 'scan_project_icons':
                return Promise.resolve([]);

            case 'read_project_icon':
                return Promise.resolve(null);

            default:
                console.warn('[GooseAndroid] Unhandled invoke command:', cmd, args);
                return Promise.resolve(null);
        }
    }

    // =========================================================================
    // Platform Detection
    // =========================================================================

    // Let the frontend know we're on Android
    window.__GOOSE_PLATFORM__ = 'android';

    // =========================================================================
    // Tauri Shell Plugin Shim
    // =========================================================================

    // The goose2 app uses tauri-plugin-shell for sidecar management
    // On Android, the binary is managed by GooseService instead
    window.__TAURI_PLUGIN_SHELL__ = {
        Command: {
            sidecar: function() {
                return {
                    execute: function() {
                        return Promise.resolve({ code: 0, stdout: '', stderr: '' });
                    }
                };
            }
        }
    };

    console.log('[GooseAndroid] Platform shim loaded. Port:', window.GOOSE_PORT);
})();
