package org.jjgroup.xproxy.project.core

import java.nio.file.Path
import java.nio.file.Paths

object ProjectPaths {
    val runtimeRoot: Path = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
    val defaultProjectsRoot: Path = Paths.get(System.getProperty("user.home"))
        .resolve("xproxy")
        .resolve("projects")
        .toAbsolutePath()
        .normalize()
    val projectsRoot: Path = defaultProjectsRoot
    val globalRoot: Path = Paths.get(System.getProperty("user.home")).resolve(".xproxy")
    val globalDbPath: Path = globalRoot.resolve("xproxy.db")
}
