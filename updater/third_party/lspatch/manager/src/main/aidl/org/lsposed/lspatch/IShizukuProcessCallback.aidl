package org.lsposed.lspatch;

/**
 * How the shell service tells the manager that a companion (a module's own app) process has just
 * started. One-way: the shell, which owns the observer, must never block on the manager, which may
 * be the very process that was reaped.
 */
oneway interface IShizukuProcessCallback {
    // Fired on the not-running -> running edge of a watched companion package (and once at
    // registration for one already running), so the manager can push that companion its service.
    void onCompanionStarted(String packageName);
}
