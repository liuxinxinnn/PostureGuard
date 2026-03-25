(function(){
  'use strict';

  const canvas = document.getElementById('canvas');
  const ctx = canvas.getContext('2d');
  const statusEl = document.getElementById('status');
  const perfEl = document.getElementById('perf');

  let port = null;
  let lastPose = null; // Float32-like array [33*4]
  let coachPose = null;
  let lastScore = null;
  let errorBones = [];

  let frames = 0;
  let lastPerfT = performance.now();

  function resize(){
    const dpr = Math.max(1, Math.min(3, window.devicePixelRatio || 1));
    canvas.width = Math.floor(window.innerWidth * dpr);
    canvas.height = Math.floor(window.innerHeight * dpr);
    ctx.setTransform(dpr,0,0,dpr,0,0);
  }
  window.addEventListener('resize', resize);
  resize();

  // MediaPipe pose landmark indices.
  const BONES = [
    [11,12], [11,13], [13,15], [12,14], [14,16],
    [11,23], [12,24], [23,24],
    [23,25], [25,27], [24,26], [26,28],
    [0,11], [0,12]
  ];

  // Map Android error labels (Chinese) to skeleton segments.
  const ERROR_TO_BONES = {
    '左上臂': [[11,13]],
    '右上臂': [[12,14]],
    '左前臂': [[13,15]],
    '右前臂': [[14,16]],
    '躯干': [[11,23],[12,24],[23,24]],
    '肩线': [[11,12]],
    '左肘': [[11,13],[13,15]],
    '右肘': [[12,14],[14,16]],
    '左肩': [[11,12],[11,13]],
    '右肩': [[11,12],[12,14]],
    '左大腿': [[23,25]],
    '右大腿': [[24,26]],
    '左小腿': [[25,27]],
    '右小腿': [[26,28]],
    '髋线': [[23,24]],
    '左膝': [[23,25],[25,27]],
    '右膝': [[24,26],[26,28]],
    '髋': [[11,23],[12,24],[23,24]]
  };

  function isBoneError(a, b){
    if(!errorBones || errorBones.length === 0) return false;
    for(const label of errorBones){
      const bones = ERROR_TO_BONES[label];
      if(!bones) continue;
      for(const pair of bones){
        const x = pair[0];
        const y = pair[1];
        if((a === x && b === y) || (a === y && b === x)) return true;
      }
    }
    return false;
  }
  function drawSkeleton(landmarks, offsetX, color, glow){
    if(!landmarks || landmarks.length < 33*4) return;

    const W = window.innerWidth;
    const H = window.innerHeight;

    function xy(i){
      const x = landmarks[i*4 + 0];
      const y = landmarks[i*4 + 1];
      const z = landmarks[i*4 + 2];
      const v = landmarks[i*4 + 3];
      if(!(x >= 0 && x <= 1 && y >= 0 && y <= 1) || v < 0.2) return null;

      // Cheap 3D-ish projection: MediaPipe z is roughly "into the screen".
      // We make closer points slightly larger and shift towards the viewer.
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

    for(const [a,b] of BONES){
      const pa = xy(a); const pb = xy(b);
      if(!pa || !pb) continue;
      const err = isBoneError(a,b);

      ctx.strokeStyle = err ? 'rgba(231,111,81,0.95)' : color;
      if(glow){
        ctx.shadowColor = err ? 'rgba(231,111,81,0.7)' : 'rgba(61,224,230,0.5)';
        ctx.shadowBlur = err ? 18 : 12;
      } else {
        ctx.shadowBlur = 0;
      }
      ctx.beginPath();
      ctx.moveTo(pa[0], pa[1]);
      ctx.lineTo(pb[0], pb[1]);
      ctx.stroke();
    }

    ctx.shadowBlur = 0;
  }

  function loop(){
    frames++;

    const W = window.innerWidth;
    const H = window.innerHeight;
    ctx.clearRect(0,0,W,H);

    // Two panels
    ctx.fillStyle = 'rgba(21,40,59,0.35)';
    ctx.fillRect(0,0,W*0.5,H);
    ctx.fillRect(W*0.5,0,W*0.5,H);

    ctx.fillStyle = 'rgba(255,255,255,0.85)';
    ctx.font = '600 12px system-ui, -apple-system, Segoe UI, Roboto, sans-serif';
    ctx.fillText('你(数字孪生)', 14, 22);
    ctx.fillText('教练(对齐目标)', W*0.5 + 14, 22);

    drawSkeleton(lastPose, 0, 'rgba(61,224,230,0.9)', true);
    drawSkeleton(coachPose || lastPose, W*0.5, 'rgba(244,201,93,0.85)', true);

    if(lastScore != null){
      ctx.fillStyle = 'rgba(255,255,255,0.9)';
      ctx.font = '700 42px system-ui, -apple-system, Segoe UI, Roboto, sans-serif';
      ctx.fillText(String(Math.round(lastScore)), 18, H - 22);
    }

    const now = performance.now();
    if(now - lastPerfT >= 1000){
      const fps = (frames * 1000) / (now - lastPerfT);
      perfEl.textContent = 'render fps=' + fps.toFixed(1);
      frames = 0;
      lastPerfT = now;
    }

    requestAnimationFrame(loop);
  }
  requestAnimationFrame(loop);

  function parseBinaryPoseFrame(ab){
    try{
      const dv = new DataView(ab);
      const magic = dv.getUint32(0, true);
      if(magic !== 0x50475032) return false; // 'PGP2'
      const type = dv.getUint32(4, true);
      if(type !== 1) return false; // POSE_FRAME
      const floatCount = dv.getUint32(20, true);
      if(floatCount <= 0) return false;
      lastPose = new Float32Array(ab, 24, floatCount);
      return true;
    } catch(e){
      return false;
    }
  }

  function onPortMessage(ev){
    const msg = ev.data;

    if(msg instanceof ArrayBuffer){
      if(parseBinaryPoseFrame(msg)){
        statusEl.textContent = '收到姿态帧(二进制)';
      }
      return;
    }

    if(typeof msg !== 'string') return;
    let obj = null;
    try { obj = JSON.parse(msg); } catch(e) { return; }

    if(obj.type === 'POSE_FRAME'){
      lastPose = obj.landmarks;
      statusEl.textContent = '收到姿态帧: ' + (obj.t || 0);
    } else if(obj.type === 'COACH_STATE'){
      lastScore = obj.score;
      errorBones = obj.errors || [];
    } else if(obj.type === 'COACH_POSE'){
      coachPose = obj.landmarks;
    }
  }
  // Receive init message and port from Android.
  window.addEventListener('message', function(event){
    if(event.data !== 'init') return;
    if(!event.ports || event.ports.length < 1) return;
    port = event.ports[0];
    port.onmessage = onPortMessage;
    statusEl.textContent = 'WebMessagePort 已连接';
    try { port.postMessage(JSON.stringify({ type: 'CAPS', binaryPose: true })); } catch(e) {}
    // Reply (optional)
    try { port.postMessage(JSON.stringify({ type: 'HELLO', t: Date.now() })); } catch(e) {}
  });
})();