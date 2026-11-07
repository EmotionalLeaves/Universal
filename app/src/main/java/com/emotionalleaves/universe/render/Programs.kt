package com.emotionalleaves.universe.render

/** All shader programs and shared meshes, created on the GL thread. */
class Programs {
    val points = Program(Shaders.POINTS_VS, Shaders.POINTS_FS)
    val sphere = Program(Shaders.SPHERE_VS, Shaders.SPHERE_FS)
    val atmosphere = Program(Shaders.SPHERE_VS, Shaders.ATMO_FS)
    val line = Program(Shaders.LINE_VS, Shaders.LINE_FS)
    val billboard = Program(Shaders.BILLBOARD_VS, Shaders.BILLBOARD_FS)
    val ring = Program(Shaders.RING_VS, Shaders.RING_FS)
    val terrain = Program(Shaders.TERRAIN_VS, Shaders.TERRAIN_FS)

    val sphereMesh = Meshes.sphere()
    val lowSphereMesh = Meshes.sphere(16, 32)
    val quad = Meshes.quad()
    val cube = Meshes.cube()
}
