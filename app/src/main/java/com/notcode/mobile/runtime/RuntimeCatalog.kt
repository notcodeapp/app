package com.notcode.mobile.runtime

internal object RuntimeCatalog {
    private const val termux = "https://packages.termux.dev/apt/termux-main/"
    fun ubuntu(abi: String) = if (abi == "arm64-v8a") RuntimeArtifact(
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz",
        "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2", 64L * 1024 * 1024)
    else RuntimeArtifact("https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-amd64.tar.gz",
        "e77b6f10c2590cef872b33ee9f635a0e3fd1f57fb074c0e52b5c7f56147a0c86", 64L * 1024 * 1024)

    fun engine(abi: String): List<RuntimeArtifact> = if (abi == "arm64-v8a") listOf(
        RuntimeArtifact(termux + "pool/main/p/proot/proot_5.1.107.102_aarch64.deb", "10a394b585c2fc2b2c8502544f4e5f1be581dc8bfff79e8ba62f16207423e030", 1024 * 1024),
        RuntimeArtifact(termux + "pool/main/libt/libtalloc/libtalloc_2.5.0_aarch64.deb", "556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7", 1024 * 1024),
        RuntimeArtifact(termux + "pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb", "0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6", 1024 * 1024),
    ) else listOf(
        RuntimeArtifact(termux + "pool/main/p/proot/proot_5.1.107.102_x86_64.deb", "2497064226c15971be8131c7facead94a06c225347796326a383c832dca57758", 1024 * 1024),
        RuntimeArtifact(termux + "pool/main/libt/libtalloc/libtalloc_2.5.0_x86_64.deb", "b8c6d95f20075dc1f9ec6573575b2444e8d526e48e0d8d6d5cf4e071e6e06530", 1024 * 1024),
        RuntimeArtifact(termux + "pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_x86_64.deb", "ffa9e4c87467b158b148d0ff92dda796aa038276c2075af3269cdcdb06f25797", 1024 * 1024),
    )

    fun powershell(abi: String) = if (abi == "arm64-v8a") RuntimeArtifact(
        "https://github.com/PowerShell/PowerShell/releases/download/v7.4.17/powershell-7.4.17-linux-arm64.tar.gz",
        "68f3874cdb6cd564acf404103dfc410ee85435b02f0ad648e73a958853175d6c", 128L * 1024 * 1024)
    else RuntimeArtifact("https://github.com/PowerShell/PowerShell/releases/download/v7.4.17/powershell-7.4.17-linux-x64.tar.gz",
        "dcfe6060fc86abcb859ce1f8f80843ce50bab0585396de56380ed9f25176ac6d", 128L * 1024 * 1024)
}
