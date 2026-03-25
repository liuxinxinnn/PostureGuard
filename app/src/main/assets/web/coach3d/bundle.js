/* PostureGuard Coach3D (WebView)
 * - Receives MediaPipe Pose via WebMessagePort (JSON)
 * - Renders 2D skeleton overlay
 * - Renders two VRM avatars (user/coach) with Kalidokit when available
 */

const glCanvas = document.getElementById('gl');
const overlayCanvas = document.getElementById('overlay');
const statusEl = document.getElementById('status');
const scoreEl = document.getElementById('score');
const perfEl = document.getElementById('perf');

const ctx = overlayCanvas.getContext('2d');

let port = null;

let userPose = null;   // Float array [33*4] (normalized)
let userWorld = null;  // Float array [33*4] (world)
let coachPose = null;
let coachWorld = null;

let lastScore = null;
let errorIds = [];
let errorLabels = [];

let frames = 0;
let lastPerfT = performance.now();

function resizeOverlay() {
  const dpr = Math.max(1, Math.min(3, window.devicePixelRatio || 1));
  overlayCanvas.width = Math.floor(window.innerWidth * dpr);
  overlayCanvas.height = Math.floor(window.innerHeight * dpr);
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
}
window.addEventListener('resize', resizeOverlay);
resizeOverlay();

// MediaPipe pose landmark indices.
const BONES = [
  [11, 12], [11, 13], [13, 15], [12, 14], [14, 16],
  [11, 23], [12, 24], [23, 24],
  [23, 25], [25, 27], [24, 26], [26, 28],
  [0, 11], [0, 12],
];

// Stable ASCII error ids (sent from Android) -> bone pairs for highlighting.
const ERROR_ID_TO_BONES = {
  L_UPPER_ARM: [[11, 13]],
  R_UPPER_ARM: [[12, 14]],
  L_FOREARM: [[13, 15]],
  R_FOREARM: [[14, 16]],
  TORSO: [[11, 23], [12, 24]],
  SHOULDER_LINE: [[11, 12]],
  L_ELBOW: [[11, 13], [13, 15]],
  R_ELBOW: [[12, 14], [14, 16]],
  L_SHOULDER: [[11, 12], [11, 13]],
  R_SHOULDER: [[11, 12], [12, 14]],
  L_THIGH: [[23, 25]],
  R_THIGH: [[24, 26]],
  L_SHIN: [[25, 27]],
  R_SHIN: [[26, 28]],
  HIP_LINE: [[23, 24]],
  L_KNEE: [[23, 25], [25, 27]],
  R_KNEE: [[24, 26], [26, 28]],
  HIP_HINGE: [[11, 23], [12, 24], [23, 24]],
};

function isBoneError(a, b) {
  if (!errorIds || errorIds.length === 0) return false;
  for (const id of errorIds) {
    const bones = ERROR_ID_TO_BONES[id];
    if (!bones) continue;
    for (const pair of bones) {
      const x = pair[0];
      const y = pair[1];
      if ((a === x && b === y) || (a === y && b === x)) return true;
    }
  }
  return false;
}

function drawSkeleton(landmarks, offsetX, color) {
  if (!landmarks || landmarks.length < 33 * 4) return;

  const W = window.innerWidth;
  const H = window.innerHeight;

  function xy(i) {
    const x = landmarks[i * 4 + 0];
    const y = landmarks[i * 4 + 1];
    const z = landmarks[i * 4 + 2];
    const v = landmarks[i * 4 + 3];
    if (!(x >= 0 && x <= 1 && y >= 0 && y <= 1) || v < 0.2) return null;

    const zz = Math.max(-1.0, Math.min(1.0, -z));
    const depth = 1.0 / (1.0 + zz * 0.75);

    const panelW = W * 0.5;
    const localX = (x - 0.5) * (panelW * 0.86) * depth + panelW * 0.5;
    const localY = (y) * (H * 0.82) + H * 0.10;

    return [offsetX + localX, localY];
  }

  ctx.lineWidth = 4;
  ctx.lineCap = 'round';
  ctx.lineJoin = 'round';

  for (const [a, b] of BONES) {
    const pa = xy(a);
    const pb = xy(b);
    if (!pa || !pb) continue;

    const err = isBoneError(a, b);
    ctx.strokeStyle = err ? 'rgba(231,111,81,0.95)' : color;
    ctx.shadowColor = err ? 'rgba(231,111,81,0.75)' : 'rgba(61,224,230,0.55)';
    ctx.shadowBlur = err ? 18 : 12;

    ctx.beginPath();
    ctx.moveTo(pa[0], pa[1]);
    ctx.lineTo(pb[0], pb[1]);
    ctx.stroke();
  }

  ctx.shadowBlur = 0;
}

function loop2D() {
  frames++;
  const W = window.innerWidth;
  const H = window.innerHeight;

  ctx.clearRect(0, 0, W, H);

  // Dim a bit so skeleton/VRM pop on top of camera preview.
  ctx.fillStyle = 'rgba(10,20,30,0.18)';
  ctx.fillRect(0, 0, W, H);

  ctx.fillStyle = 'rgba(255,255,255,0.86)';
  ctx.font = '600 12px system-ui, -apple-system, Segoe UI, Roboto, sans-serif';
  ctx.fillText('YOU', 14, 22);
  ctx.fillText('COACH', W * 0.5 + 14, 22);

  drawSkeleton(userPose, 0, 'rgba(61,224,230,0.92)');
  drawSkeleton(coachPose || userPose, W * 0.5, 'rgba(244,201,93,0.85)');

  const now = performance.now();
  if (now - lastPerfT >= 1000) {
    const fps = (frames * 1000) / (now - lastPerfT);
    perfEl.textContent = 'render fps=' + fps.toFixed(1);
    frames = 0;
    lastPerfT = now;
  }

  requestAnimationFrame(loop2D);
}
requestAnimationFrame(loop2D);

function toLandmarkObjects(arr) {
  if (!arr || arr.length < 33 * 4) return null;
  const out = new Array(33);
  for (let i = 0; i < 33; i++) {
    out[i] = {
      x: arr[i * 4 + 0],
      y: arr[i * 4 + 1],
      z: arr[i * 4 + 2],
      visibility: arr[i * 4 + 3],
    };
  }
  return out;
}

// -------- WebMessagePort wiring --------
function onPortMessage(ev) {
  const msg = ev.data;
  if (typeof msg !== 'string') return;

  let obj = null;
  try { obj = JSON.parse(msg); } catch (e) { return; }

  if (obj.type === 'POSE_FRAME') {
    userPose = obj.landmarks;
    userWorld = obj.world || null;
    statusEl.textContent = 'POSE_FRAME t=' + (obj.t || 0);
  } else if (obj.type === 'COACH_POSE') {
    coachPose = obj.landmarks;
    coachWorld = obj.world || null;
  } else if (obj.type === 'COACH_STATE') {
    lastScore = obj.score;
    errorIds = obj.errorIds || obj.errors || [];
    errorLabels = obj.errorLabels || [];
    const show = (errorLabels && errorLabels.length ? errorLabels : errorIds);
    scoreEl.textContent = 'Score: ' + Math.round(lastScore || 0) + (show.length ? '  纠正: ' + show.join(' / ') : '');
  }
}

window.addEventListener('message', function (event) {
  if (event.data !== 'init') return;
  if (!event.ports || event.ports.length < 1) return;
  port = event.ports[0];
  port.onmessage = onPortMessage;
  statusEl.textContent = 'WebMessagePort connected';
  try { port.postMessage(JSON.stringify({ type: 'CAPS', binaryPose: false })); } catch (e) {}
});

// -------- 3D VRM --------
let THREE = null;
let GLTFLoader = null;
let VRMLoaderPlugin = null;
let VRMUtils = null;
let VRMHumanBoneName = null;

let renderer = null;
let scene = null;
let camera = null;
let clock = null;
let cameraTarget = null;

let userVrm = null;
let coachVrm = null;

let ERR_VRM_BONES = null;
let userErrLines = null;
let userErrPos = null;
let tmpV3A = null;
let tmpV3B = null;

function _setStatus(s) {
  try { statusEl.textContent = s; } catch (e) {}
  try { console.log('[Coach3D]', s); } catch (e) {}
}

window.addEventListener('error', (e) => {
  const msg = (e && e.message) ? e.message : String(e);
  _setStatus('JS error: ' + msg);
});
window.addEventListener('unhandledrejection', (e) => {
  const r = (e && e.reason) ? e.reason : e;
  const msg = (r && r.message) ? r.message : String(r);
  _setStatus('Promise error: ' + msg);
});

async function init3D() {
  _setStatus('3D: importing three...');
  try {
    THREE = await import('three');
    _setStatus('3D: three ok');

    // Preflight: ensure the VRM file is reachable (will show up in Network).
    try {
      fetch('./models/AliciaSolid.vrm', { cache: 'no-store' }).then((r) => {
        _setStatus('3D: vrm fetch ' + r.status);
      }).catch((e) => {
        _setStatus('3D: vrm fetch failed');
      });
    } catch (e) {}

    _setStatus('3D: importing GLTFLoader...');
    const loaderMod = await import('./GLTFLoader.js');
    GLTFLoader = loaderMod.GLTFLoader;
    _setStatus('3D: GLTFLoader ok');

    _setStatus('3D: importing three-vrm...');
    const vrmMod = await import('@pixiv/three-vrm');
    VRMLoaderPlugin = vrmMod.VRMLoaderPlugin;
    VRMUtils = vrmMod.VRMUtils;
    VRMHumanBoneName = vrmMod.VRMHumanBoneName;
    _setStatus('3D: three-vrm ok');

    ERR_VRM_BONES = {
      L_UPPER_ARM: [[VRMHumanBoneName.LeftUpperArm, VRMHumanBoneName.LeftLowerArm]],
      R_UPPER_ARM: [[VRMHumanBoneName.RightUpperArm, VRMHumanBoneName.RightLowerArm]],
      L_FOREARM: [[VRMHumanBoneName.LeftLowerArm, VRMHumanBoneName.LeftHand]],
      R_FOREARM: [[VRMHumanBoneName.RightLowerArm, VRMHumanBoneName.RightHand]],
      TORSO: [[VRMHumanBoneName.Hips, VRMHumanBoneName.Chest]],
      SHOULDER_LINE: [[VRMHumanBoneName.LeftUpperArm, VRMHumanBoneName.RightUpperArm]],
      L_ELBOW: [[VRMHumanBoneName.LeftUpperArm, VRMHumanBoneName.LeftLowerArm], [VRMHumanBoneName.LeftLowerArm, VRMHumanBoneName.LeftHand]],
      R_ELBOW: [[VRMHumanBoneName.RightUpperArm, VRMHumanBoneName.RightLowerArm], [VRMHumanBoneName.RightLowerArm, VRMHumanBoneName.RightHand]],
      L_SHOULDER: [[VRMHumanBoneName.LeftUpperArm, VRMHumanBoneName.LeftLowerArm]],
      R_SHOULDER: [[VRMHumanBoneName.RightUpperArm, VRMHumanBoneName.RightLowerArm]],
      L_THIGH: [[VRMHumanBoneName.LeftUpperLeg, VRMHumanBoneName.LeftLowerLeg]],
      R_THIGH: [[VRMHumanBoneName.RightUpperLeg, VRMHumanBoneName.RightLowerLeg]],
      L_SHIN: [[VRMHumanBoneName.LeftLowerLeg, VRMHumanBoneName.LeftFoot]],
      R_SHIN: [[VRMHumanBoneName.RightLowerLeg, VRMHumanBoneName.RightFoot]],
      HIP_LINE: [[VRMHumanBoneName.LeftUpperLeg, VRMHumanBoneName.RightUpperLeg]],
      L_KNEE: [[VRMHumanBoneName.LeftUpperLeg, VRMHumanBoneName.LeftLowerLeg]],
      R_KNEE: [[VRMHumanBoneName.RightUpperLeg, VRMHumanBoneName.RightLowerLeg]],
      HIP_HINGE: [[VRMHumanBoneName.Hips, VRMHumanBoneName.Chest]],
    };

    _setStatus('3D: setupThree...');
    setupThree();

    _setStatus('3D: loadAvatars...');
    await loadAvatars();

    _setStatus('3D ready (AliciaSolid.vrm)');
    requestAnimationFrame(loop3D);
  } catch (e) {
    const msg = (e && e.message) ? e.message : String(e);
    console.warn('3D init failed:', e);
    _setStatus('3D init failed: ' + msg);
  }
}
function setupThree() {
  renderer = new THREE.WebGLRenderer({ canvas: glCanvas, alpha: true, antialias: true, preserveDrawingBuffer: false });
  renderer.setPixelRatio(Math.max(1, Math.min(3, window.devicePixelRatio || 1)));
  renderer.setSize(window.innerWidth, window.innerHeight, false);
  renderer.setClearColor(0x000000, 0);

  scene = new THREE.Scene();

  camera = new THREE.PerspectiveCamera(30, window.innerWidth / window.innerHeight, 0.05, 200);
  camera.position.set(0, 1.35, 4.0);
  cameraTarget = new THREE.Vector3(0, 1.1, 0);

  const dir = new THREE.DirectionalLight(0xffffff, 1.2);
  dir.position.set(1.0, 1.5, 1.2);
  scene.add(dir);
  scene.add(new THREE.AmbientLight(0xffffff, 0.45));

  clock = new THREE.Clock();

  // Red neon line segments for 3D error visualization (overlays on the avatar).
  const MAX_SEG = 48;
  userErrPos = new Float32Array(MAX_SEG * 2 * 3);
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.BufferAttribute(userErrPos, 3));
  g.setDrawRange(0, 0);
  const m = new THREE.LineBasicMaterial({
    color: 0xff3b30,
    transparent: true,
    opacity: 0.92,
    depthTest: false,
    blending: THREE.AdditiveBlending,
  });
  userErrLines = new THREE.LineSegments(g, m);
  userErrLines.frustumCulled = false;
  scene.add(userErrLines);

  tmpV3A = new THREE.Vector3();
  tmpV3B = new THREE.Vector3();

  window.addEventListener('resize', () => {
    if (!renderer || !camera) return;
    renderer.setPixelRatio(Math.max(1, Math.min(3, window.devicePixelRatio || 1)));
    renderer.setSize(window.innerWidth, window.innerHeight, false);
    camera.aspect = window.innerWidth / window.innerHeight;
    camera.updateProjectionMatrix();
  });
}

function disableFrustumCulling(root) {
  root.traverse((o) => {
    if (o && (o.isMesh || o.isSkinnedMesh)) o.frustumCulled = false;
  });
}

function estimateHeightFromBones(vrm) {
  try {
    const head = vrm.humanoid.getNormalizedBoneNode(VRMHumanBoneName.Head) || vrm.humanoid.getNormalizedBoneNode(VRMHumanBoneName.Neck);
    const lf = vrm.humanoid.getNormalizedBoneNode(VRMHumanBoneName.LeftFoot);
    const rf = vrm.humanoid.getNormalizedBoneNode(VRMHumanBoneName.RightFoot);
    if (!head || (!lf && !rf)) return null;

    const vHead = tmpV3A;
    const vFoot = tmpV3B;
    head.getWorldPosition(vHead);

    let footY = Infinity;
    if (lf) { lf.getWorldPosition(vFoot); footY = Math.min(footY, vFoot.y); }
    if (rf) { rf.getWorldPosition(vFoot); footY = Math.min(footY, vFoot.y); }

    const h = vHead.y - footY;
    if (!isFinite(h) || h <= 0) return null;
    return h;
  } catch (e) {
    return null;
  }
}

async function loadVrm(url) {
  return new Promise((resolve, reject) => {
    const loader = new GLTFLoader();
    loader.register((parser) => new VRMLoaderPlugin(parser));
    loader.load(
      url,
      (gltf) => {
        const vrm = gltf.userData.vrm;
        if (!vrm) {
          reject(new Error('No vrm in gltf.userData'));
          return;
        }
        try { VRMUtils.rotateVRM0(vrm); } catch (e) {}
        resolve(vrm);
      },
      undefined,
      (err) => reject(err)
    );
  });
}

function normalizeVrm(vrm, targetHeight) {
  const obj = vrm.scene;

  obj.position.set(0, 0, 0);
  obj.scale.setScalar(1);
  obj.updateMatrixWorld(true);

  // Some sample VRMs ship without accessor min/max. Disable culling to avoid "only a bow" issues.
  disableFrustumCulling(obj);

  // Height estimation: prefer humanoid bones, fallback to bbox.
  let h = estimateHeightFromBones(vrm);
  if (!h) {
    const box = new THREE.Box3().setFromObject(obj);
    const size = new THREE.Vector3();
    box.getSize(size);
    h = size.y;
  }

  // Clamp to avoid insane scale when height is misread.
  h = Math.max(0.6, Math.min(3.0, h || 1.65));
  const s = targetHeight / h;
  obj.scale.setScalar(s);
  obj.updateMatrixWorld(true);

  // Put feet on Y=0 and center X/Z.
  const box2 = new THREE.Box3().setFromObject(obj);
  const center = new THREE.Vector3();
  box2.getCenter(center);
  obj.position.x += -center.x;
  obj.position.z += -center.z;
  obj.position.y += -box2.min.y;
  obj.updateMatrixWorld(true);
}

function fitCameraToObjects(objs) {
  if (!camera) return;
  const box = new THREE.Box3();
  for (const o of objs) {
    if (o) box.expandByObject(o);
  }
  const size = box.getSize(new THREE.Vector3());
  const center = box.getCenter(new THREE.Vector3());

  const maxDim = Math.max(size.x, size.y, size.z, 1e-3);
  const fov = camera.fov * (Math.PI / 180);
  let dist = (maxDim * 0.5) / Math.tan(fov * 0.5);
  dist *= 1.45;

  camera.position.set(center.x, center.y + size.y * 0.08, center.z + dist);
  camera.near = Math.max(0.05, dist / 100);
  camera.far = Math.max(200, dist * 100);
  camera.updateProjectionMatrix();

  cameraTarget.copy(center);
}

async function loadAvatars() {
  const url = './models/AliciaSolid.vrm';
  userVrm = await loadVrm(url);
  coachVrm = await loadVrm(url);

  normalizeVrm(userVrm, 1.65);
  normalizeVrm(coachVrm, 1.65);

  // Spread to left/right half (based on scaled bbox width).
  const box = new THREE.Box3().setFromObject(userVrm.scene);
  const size = box.getSize(new THREE.Vector3());
  const dx = Math.max(0.85, size.x * 0.6);

  userVrm.scene.position.x -= dx;
  coachVrm.scene.position.x += dx;

  scene.add(userVrm.scene);
  scene.add(coachVrm.scene);

  fitCameraToObjects([userVrm.scene, coachVrm.scene]);
}

function rigRotation(vrm, boneName, rotation, dampener, lerpAmount) {
  if (!vrm || !rotation) return;
  const bone = vrm.humanoid.getNormalizedBoneNode(boneName);
  if (!bone) return;

  const euler = new THREE.Euler(
    (rotation.x || 0) * dampener,
    (rotation.y || 0) * dampener,
    (rotation.z || 0) * dampener,
    'XYZ'
  );
  const q = new THREE.Quaternion().setFromEuler(euler);
  bone.quaternion.slerp(q, lerpAmount);
}

function applyKalidokit(vrm, pose2dArr, pose3dArr) {
  if (!vrm || !window.Kalidokit) return;
  const K = window.Kalidokit;
  if (!K.Pose || !K.Pose.solve) return;

  const pose2d = toLandmarkObjects(pose2dArr);
  const pose3d = toLandmarkObjects(pose3dArr || pose2dArr);
  if (!pose2d || !pose3d) return;

  let rig = null;
  try {
    rig = K.Pose.solve(pose3d, pose2d, { runtime: 'mediapipe' });
  } catch (e) {
    return;
  }
  if (!rig) return;

  // Avoid driving hips position (can fling avatar). Rotation only.
  rigRotation(vrm, VRMHumanBoneName.Hips, rig.Hips && rig.Hips.rotation, 0.35, 0.25);

  rigRotation(vrm, VRMHumanBoneName.Spine, rig.Spine, 0.25, 0.3);
  rigRotation(vrm, VRMHumanBoneName.Chest, rig.Chest, 0.25, 0.3);
  rigRotation(vrm, VRMHumanBoneName.Neck, rig.Neck, 0.25, 0.3);

  rigRotation(vrm, VRMHumanBoneName.LeftUpperArm, rig.LeftUpperArm, 1.0, 0.3);
  rigRotation(vrm, VRMHumanBoneName.LeftLowerArm, rig.LeftLowerArm, 1.0, 0.3);
  rigRotation(vrm, VRMHumanBoneName.RightUpperArm, rig.RightUpperArm, 1.0, 0.3);
  rigRotation(vrm, VRMHumanBoneName.RightLowerArm, rig.RightLowerArm, 1.0, 0.3);

  rigRotation(vrm, VRMHumanBoneName.LeftUpperLeg, rig.LeftUpperLeg, 1.0, 0.3);
  rigRotation(vrm, VRMHumanBoneName.LeftLowerLeg, rig.LeftLowerLeg, 1.0, 0.3);
  rigRotation(vrm, VRMHumanBoneName.RightUpperLeg, rig.RightUpperLeg, 1.0, 0.3);
  rigRotation(vrm, VRMHumanBoneName.RightLowerLeg, rig.RightLowerLeg, 1.0, 0.3);
}

function update3DErrorLines(vrm, ids) {
  if (!userErrLines || !vrm || !ERR_VRM_BONES || !ids) return;
  const pos = userErrPos;
  let k = 0;

  for (const id of ids) {
    const pairs = ERR_VRM_BONES[id];
    if (!pairs) continue;

    for (const pair of pairs) {
      if (k + 6 > pos.length) break;
      const aName = pair[0];
      const bName = pair[1];
      const a = vrm.humanoid.getNormalizedBoneNode(aName);
      const b = vrm.humanoid.getNormalizedBoneNode(bName);
      if (!a || !b) continue;

      a.getWorldPosition(tmpV3A);
      b.getWorldPosition(tmpV3B);

      pos[k++] = tmpV3A.x; pos[k++] = tmpV3A.y; pos[k++] = tmpV3A.z;
      pos[k++] = tmpV3B.x; pos[k++] = tmpV3B.y; pos[k++] = tmpV3B.z;
    }
  }

  userErrLines.geometry.setDrawRange(0, k / 3);
  userErrLines.geometry.attributes.position.needsUpdate = true;
}

function loop3D() {
  const dt = clock ? clock.getDelta() : 0.016;
  if (userVrm) userVrm.update(dt);
  if (coachVrm) coachVrm.update(dt);

  applyKalidokit(userVrm, userPose, userWorld);
  applyKalidokit(coachVrm, coachPose || userPose, coachWorld || userWorld);

  update3DErrorLines(userVrm, errorIds);

  if (renderer && scene && camera) {
    camera.lookAt(cameraTarget);
    renderer.render(scene, camera);
  }

  requestAnimationFrame(loop3D);
}

init3D();

