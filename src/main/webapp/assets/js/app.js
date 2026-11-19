(function () {
  'use strict';
  var $ = function (s) { return document.querySelector(s); };
  var EXT = ['mp4', 'mov', 'mkv', 'avi', 'webm', 'm4v', 'mpg', 'mpeg', '3gp'];
  var caps = null, file = null, preset = 'AUTO', jobId = null, timer = null, xhr = null, fails = 0, srcUrl = null, KEY = 'reelboost-job';

  function show(id) { ['stepUpload', 'stepStyle', 'stepProg', 'stepDone'].forEach(function (s) { $('#' + s).hidden = true; }); id.split(',').forEach(function (s) { $('#' + s).hidden = false; }); }
  function err(m) { $('#err').textContent = m || ''; }
  function mb(b) { return b >= 1048576 ? (b / 1048576).toFixed(1) + ' MB' : Math.round(b / 1024) + ' KB'; }
  function dur(s) { s = Math.round(s); var m = Math.floor(s / 60); return m + ':' + ('0' + (s % 60)).slice(-2); }
  function api(path, opt) { return fetch('api/' + path, opt).then(function (r) { return r.json().catch(function () { return {}; }).then(function (j) { if (!r.ok) throw new Error(j.error || ('Server error ' + r.status)); return j; }); }); }

  // ---- capabilities ----
  api('capabilities').then(function (c) {
    caps = c;
    $('#limits').textContent = 'MP4, MOV, MKV, AVI, WEBM  |  max ' + c.maxUploadMb + ' MB, ' + Math.round(c.maxDurationSec / 60) + ' min';
    if (!c.ffmpeg) { var w = $('#warn'); w.hidden = false; w.textContent = 'Server par FFmpeg install nahi hai, isliye enhance abhi kaam nahi karega. Admin: sudo apt install ffmpeg'; }
    if (!c.vidstab) { $('#stabw').classList.add('off'); $('#stab').disabled = true; $('#stabw').title = 'Server FFmpeg mein vidstab nahi hai'; }
    $('#presets').innerHTML = c.presets.map(function (p) {
      return '<button class="pc" type="button" role="radio" aria-checked="false" data-id="' + p.id + '"><b>' + p.label + '</b><span>' + p.description + '</span></button>';
    }).join('');
    $('#presets').addEventListener('click', function (e) { var b = e.target.closest('.pc'); if (b) pick(b.dataset.id); });
    pick('AUTO'); resume();
  }).catch(function (e) { var w = $('#warn'); w.hidden = false; w.textContent = 'Server se connect nahi ho paaya: ' + e.message; });

  function pick(id) {
    preset = id;
    document.querySelectorAll('.pc').forEach(function (b) { b.setAttribute('aria-checked', b.dataset.id === id ? 'true' : 'false'); });
    var p = caps.presets.filter(function (x) { return x.id === id; })[0];
    if (p) { $('#denoise').value = p.denoise; $('#sharpen').value = p.sharpen; $('#color').value = p.color; }
  }

  // ---- file selection ----
  var drop = $('#drop'), inp = $('#file');
  drop.addEventListener('click', function () { if (!file) inp.click(); });
  drop.addEventListener('keydown', function (e) { if (!file && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); inp.click(); } });
  inp.addEventListener('change', function () { setFile(inp.files[0]); });
  ['dragenter', 'dragover'].forEach(function (ev) { drop.addEventListener(ev, function (e) { e.preventDefault(); drop.classList.add('over'); }); });
  ['dragleave', 'drop'].forEach(function (ev) { drop.addEventListener(ev, function (e) { e.preventDefault(); drop.classList.remove('over'); }); });
  drop.addEventListener('drop', function (e) { setFile(e.dataTransfer.files[0]); });
  $('#change').addEventListener('click', function (e) { e.stopPropagation(); clearFile(); inp.click(); });

  function clearFile() { file = null; inp.value = ''; if (srcUrl) URL.revokeObjectURL(srcUrl); srcUrl = null; $('.pv').hidden = true; $('.ph').hidden = false; drop.classList.remove('has'); $('#stepStyle').hidden = true; }
  function setFile(f) {
    err('');
    if (!f) return;
    var ext = f.name.split('.').pop().toLowerCase();
    if (EXT.indexOf(ext) < 0) { err('Sirf video file chuno (MP4, MOV, MKV, AVI, WEBM).'); return; }
    if (caps && f.size > caps.maxUploadMb * 1048576) { err('File ' + caps.maxUploadMb + ' MB se badi hai.'); return; }
    file = f; if (srcUrl) URL.revokeObjectURL(srcUrl);
    srcUrl = URL.createObjectURL(f);
    var v = $('#srcv'); v.src = srcUrl;
    $('#fname').textContent = f.name; $('#fmeta').textContent = mb(f.size) + ' | loading...';
    v.onloadedmetadata = function () {
      $('#fmeta').textContent = mb(f.size) + ' | ' + dur(v.duration) + ' | ' + v.videoWidth + 'x' + v.videoHeight;
      if (caps && v.duration > caps.maxDurationSec) err('Video ' + Math.round(caps.maxDurationSec / 60) + ' minute se lamba hai, server use reject karega.');
    };
    v.onerror = function () { $('#fmeta').textContent = mb(f.size) + ' | preview nahi ban saka (server phir bhi process kar sakta hai)'; };
    $('.pv').hidden = false; $('.ph').hidden = true; drop.classList.add('has');
    $('#stepStyle').hidden = false;
    $('#stepStyle').scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }

  // ---- upload + poll ----
  $('#go').addEventListener('click', function () {
    if (!file) return;
    err('');
    var fd = new FormData();
    fd.append('video', file);
    fd.append('preset', preset);
    ['height', 'quality', 'denoise', 'sharpen', 'color'].forEach(function (k) { fd.append(k, $('#' + k).value); });
    fd.append('audio', $('#audio').checked ? '1' : '0');
    fd.append('stabilize', $('#stab').checked && !$('#stab').disabled ? '1' : '0');
    show('stepProg'); $('#ptitle').textContent = 'Upload ho raha hai'; setProg(0, 'Uploading video'); $('#peta').textContent = '';
    xhr = new XMLHttpRequest();
    xhr.open('POST', 'api/jobs');
    xhr.upload.onprogress = function (e) { if (e.lengthComputable) $('#peta').textContent = 'Upload ' + Math.round(e.loaded / e.total * 100) + '%  (' + mb(e.loaded) + ' / ' + mb(e.total) + ')'; };
    xhr.onload = function () {
      var j = {}; try { j = JSON.parse(xhr.responseText); } catch (x) { }
      if (xhr.status !== 202) { fail(j.error || ('Upload fail hua (' + xhr.status + ')')); return; }
      jobId = j.id; try { sessionStorage.setItem(KEY, jobId); } catch (x) { }
      $('#ptitle').textContent = 'Enhance ho raha hai'; fails = 0; poll();
    };
    xhr.onerror = function () { fail('Upload ke dauran connection toot gaya.'); };
    xhr.onabort = function () { };
    xhr.send(fd);
  });

  function setProg(p, stage) {
    $('#fill').style.width = p + '%'; $('#ppct').textContent = Math.round(p) + '%'; $('#pstage').textContent = stage;
    document.querySelector('.bar').setAttribute('aria-valuenow', Math.round(p));
  }
  function poll() {
    clearTimeout(timer);
    api('jobs/' + jobId).then(function (j) {
      fails = 0;
      if (j.state === 'DONE') { finish(j); return; }
      if (j.state === 'FAILED') { fail(j.error || 'Processing fail hua.'); return; }
      if (j.state === 'CANCELED') { reset(); err('Job cancel ho gaya.'); return; }
      setProg(j.progress, j.stage);
      $('#peta').textContent = j.eta >= 0 ? 'Lagbhag ' + dur(j.eta) + ' baaki' : (j.state === 'QUEUED' ? 'Queue mein intezaar...' : 'Estimating...');
      timer = setTimeout(poll, 1000);
    }).catch(function (e) {
      if (++fails > 6) { fail('Server se connection toot gaya: ' + e.message); return; }
      timer = setTimeout(poll, 2000);
    });
  }
  function fail(m) { clearTimeout(timer); try { sessionStorage.removeItem(KEY); } catch (x) { } show(file ? 'stepUpload,stepStyle' : 'stepUpload'); err(m); }
  function reset() { clearTimeout(timer); try { sessionStorage.removeItem(KEY); } catch (x) { } jobId = null; show(file ? 'stepUpload,stepStyle' : 'stepUpload'); }
  $('#cancel').addEventListener('click', function () {
    if (jobId) api('jobs/' + jobId + '/cancel', { method: 'POST' }).catch(function () { }).then(reset);
    else if (xhr) { xhr.abort(); reset(); }
  });

  // ---- result ----
  function finish(j) {
    clearTimeout(timer);
    show('stepDone');
    var i = j.input, o = j.output || i, base = 'api/jobs/' + j.id + '/';
    $('#stats').innerHTML = '<span>Resolution <b>' + i.width + 'x' + i.height + '</b> &rarr; <b>' + o.width + 'x' + o.height + '</b></span><span>Size <b>' + mb(i.bytes) + '</b> &rarr; <b>' + mb(o.bytes) + '</b></span><span>Length <b>' + dur(i.duration) + '</b></span><span>Style <b>' + j.options.preset + '</b></span>';
    var cmp = $('#cmp'), a = $('#imgA'), b = $('#imgB');
    cmp.classList.remove('noimg'); a.onerror = function () { cmp.classList.add('noimg'); };
    a.src = base + 'after.jpg?t=' + Date.now(); b.src = base + 'before.jpg?t=' + Date.now();
    $('#rng').value = 50; slide(50);
    var va = $('#va'), vb = $('#vb');
    va.src = base + 'output'; vb.src = srcUrl || '';
    $('#dl').href = base + 'output?download=1';
    syncVideos(va, vb);
    $('#stepDone').scrollIntoView({ behavior: 'smooth', block: 'start' });
  }
  function slide(v) { $('#imgB').style.clipPath = 'inset(0 ' + (100 - v) + '% 0 0)'; document.querySelector('.cmp .line').style.left = v + '%'; }
  $('#rng').addEventListener('input', function () { slide(this.value); });
  function syncVideos(va, vb) {
    va.onplay = function () { vb.currentTime = va.currentTime; vb.play().catch(function () { }); };
    va.onpause = function () { vb.pause(); };
    va.onseeked = function () { vb.currentTime = va.currentTime; };
  }
  $('#again').addEventListener('click', function () { $('#va').pause(); clearFile(); jobId = null; try { sessionStorage.removeItem(KEY); } catch (x) { } err(''); show('stepUpload'); window.scrollTo({ top: $('#studio').offsetTop - 80, behavior: 'smooth' }); });
  $('#del').addEventListener('click', function () {
    if (!jobId) return;
    api('jobs/' + jobId, { method: 'DELETE' }).catch(function () { }).then(function () { $('#va').removeAttribute('src'); $('#del').disabled = true; $('#dl').removeAttribute('href'); $('#dl').textContent = 'Server se hata diya gaya'; });
  });

  // reopen after refresh
  function resume() {
    var id = null; try { id = sessionStorage.getItem(KEY); } catch (x) { }
    if (!id) return;
    api('jobs/' + id).then(function (j) {
      jobId = id;
      if (j.state === 'DONE') { finish(j); }
      else if (j.state === 'PROCESSING' || j.state === 'QUEUED') { show('stepProg'); $('#ptitle').textContent = 'Enhance ho raha hai'; poll(); }
    }).catch(function () { try { sessionStorage.removeItem(KEY); } catch (x) { } });
  }
})();
