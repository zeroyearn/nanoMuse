package com.openminis.rclone.gomobile;

public final class Gomobile {
    private Gomobile() {}

    public static void rcloneInitialize() {
        // Remote-first stub: rclone gomobile runtime disabled in remote mode
    }

    public static void rcloneFinalize() {
    }

    public static RcloneRPCResult rcloneRPC(String method, String input) {
        return new RcloneRPCResult("{}", 200L);
    }
}
