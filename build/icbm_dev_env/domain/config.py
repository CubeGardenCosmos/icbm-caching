"""版本冻结常量与资源 URL（唯一来源 docs/VERSION_FREEZE.md）。

修改版本时必须同步更新 docs/VERSION_FREEZE.md、本文件与 AGENTS.md。
"""

from __future__ import annotations

# --- 冻结版本快照（26.3-snapshot-8，来源 piston-meta / Fabric meta） ---
MINECRAFT_VERSION = "26.3-snapshot-8"
PROTOCOL_VERSION = 1073742154
DATA_VERSION = 5010
RESOURCE_PACK_MAJOR = 96
DATA_PACK_MAJOR = 116
JAVA_MAJOR = 25
FABRIC_LOADER = "0.19.3"
FABRIC_API = "0.157.1+26.3"
FABRIC_LOOM = "1.17.19"
FABRIC_INSTALLER = "1.1.2"
VINEFLOWER = "1.12.0"
GRADLE_VERSION = "9.5.1"

# --- 参考仓库 ---
FABRIC_REF_URL = "https://github.com/FabricMC/fabric.git"
FABRIC_REF_BRANCHES = ("26.3", "26.2", "main")
VELOCITY_REF_URL = "https://github.com/PaperMC/Velocity.git"
VELOCITY_REF_BRANCH = "master"

# --- 下载源 ---
ADOPTIUM_API = (
    "https://api.adoptium.net/v3/assets/latest/25/hotspot"
    "?os=linux&arch=x64&image_type=jdk"
)
TUNA_JDK_PATTERN = "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/25/jdk/x64/linux/{filename}"
PISTON_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
FABRIC_META = "https://meta.fabricmc.net/v2"
FABRIC_MAVEN = "https://maven.fabricmc.net"
INSTALLER_JAR_URL = (
    f"{FABRIC_MAVEN}/net/fabricmc/fabric-installer/{FABRIC_INSTALLER}"
    f"/fabric-installer-{FABRIC_INSTALLER}.jar"
)
VINEFLOWER_RELEASE_URL = (
    f"https://github.com/Vineflower/vineflower/releases/download/"
    f"{VINEFLOWER}/vineflower-{VINEFLOWER}.jar"
)
