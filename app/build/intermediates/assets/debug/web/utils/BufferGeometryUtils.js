// Minimal BufferGeometryUtils for GLTFLoader.js (three r154).
// We only implement toTrianglesDrawMode, which is required for TRIANGLE_STRIP/FAN primitives.

import { TriangleFanDrawMode, TriangleStripDrawMode } from "three";

export function toTrianglesDrawMode(geometry, drawMode) {
  // If already triangles (or unknown), return as-is.
  if (drawMode !== TriangleFanDrawMode && drawMode !== TriangleStripDrawMode) return geometry;

  const index = geometry.getIndex();
  const position = geometry.getAttribute("position");
  if (!position) return geometry;

  const src = index ? index.array : null;
  const vertexCount = index ? index.count : position.count;
  const triCount = Math.max(0, vertexCount - 2);

  const dst = new Array(triCount * 3);
  let di = 0;

  function get(i) {
    return src ? src[i] : i;
  }

  if (drawMode === TriangleFanDrawMode) {
    // (0, i, i+1)
    const a = get(0);
    for (let i = 1; i < vertexCount - 1; i++) {
      dst[di++] = a;
      dst[di++] = get(i);
      dst[di++] = get(i + 1);
    }
  } else {
    // TriangleStripDrawMode
    // (i, i+1, i+2) with alternating winding.
    for (let i = 0; i < vertexCount - 2; i++) {
      if (i % 2 === 0) {
        dst[di++] = get(i);
        dst[di++] = get(i + 1);
        dst[di++] = get(i + 2);
      } else {
        dst[di++] = get(i + 2);
        dst[di++] = get(i + 1);
        dst[di++] = get(i);
      }
    }
  }

  const newGeometry = geometry.clone();
  newGeometry.setIndex(dst);
  return newGeometry;
}
