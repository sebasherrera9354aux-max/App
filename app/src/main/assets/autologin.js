// Auto-login para la página de inicio de sesión del RCMS (Hikvision).
// Se llama como: (función)(usuario, clave, modo, soloVisibles)
//   modo 'detect' -> devuelve 'FORM' o 'NOFORM'
//   modo 1,2,3    -> llena el formulario y lo envía con una estrategia distinta por intento
function (u, p, mode, onlyVisible) {
  function txt(o) { return typeof o === 'string' ? o : ''; }
  function visible(el) {
    if (!el) return false;
    var r = el.getBoundingClientRect();
    return el.offsetParent !== null || (r.width > 0 && r.height > 0);
  }
  function docs() {
    var out = [document];
    var frames = document.querySelectorAll('iframe,frame');
    for (var i = 0; i < frames.length; i++) {
      try { if (frames[i].contentDocument) out.push(frames[i].contentDocument); } catch (e) {}
    }
    return out;
  }
  var PW_RE = /pwd|pass|clave|contrase|密码/i;
  var USER_RE = /loginname|user|usuario|account|cuenta|login|name|用户/i;
  var BTN_RE = /log ?in|sign ?in|submit|iniciar|ingresar|entrar|acceder|登\s*录/i;

  function attrs(el) {
    return (txt(el.name) + ' ' + txt(el.id) + ' ' + txt(el.placeholder) + ' ' +
            (el.getAttribute ? (el.getAttribute('class') || '') : '')).toLowerCase();
  }

  function findPasswords(d) {
    var all = d.querySelectorAll('input');
    var res = [];
    for (var i = 0; i < all.length; i++) {
      var el = all[i], t = (el.getAttribute('type') || 'text').toLowerCase();
      if (t === 'hidden' && !PW_RE.test(txt(el.name) + txt(el.id))) continue;
      if (t === 'password' || (PW_RE.test(txt(el.name) + ' ' + txt(el.id)) && t !== 'checkbox' && t !== 'hidden')) {
        if (onlyVisible && !visible(el)) continue;
        res.push(el);
      }
    }
    return res;
  }

  var ds = docs(), doc = null, pws = [];
  for (var k = 0; k < ds.length; k++) {
    var f = findPasswords(ds[k]);
    if (f.length) { doc = ds[k]; pws = f; break; }
  }
  if (!doc) {
    // Aviso de sesión vencida ("User account or authorization expired", etc.)
    var EXP_RE = /authorization\s*ex\s*pired|account or authorization|session (has )?(expired|timed? ?out)|not logged|please log ?in|\u8fc7\u671f|\u8d85\u65f6|\u91cd\u65b0\u767b\u5f55|sesi[o\u00f3]n (expir|venc|caduc)/i;
    for (var q = 0; q < ds.length; q++) {
      var nodes = ds[q].querySelectorAll('div,p,span,td,label');
      for (var z = 0; z < nodes.length; z++) {
        var n = nodes[z];
        var t = txt(n.innerText || n.textContent);
        if (t.length > 0 && t.length < 200 && EXP_RE.test(t.replace(/\s+/g, ' ')) && visible(n)) {
          if (mode === 'ok') {
            // Pulsar el botón OK / Aceptar / 确定 del aviso
            var sels = ['button,input[type=button],input[type=submit]', 'a', 'span', 'div'];
            for (var g = 0; g < sels.length; g++) {
              var bs = ds[q].querySelectorAll(sels[g]);
              for (var y = 0; y < bs.length; y++) {
                var bt = txt(bs[y].innerText || bs[y].value).trim();
                if (/^(ok|aceptar|accept|confirm|\u786e\u5b9a|\u786e\u8ba4)$/i.test(bt) && visible(bs[y])) { bs[y].click(); return 'CLICKED'; }
              }
            }
            return 'NOBUTTON';
          }
          return 'EXPIRED';
        }
      }
    }
    return 'NOFORM';
  }
  if (mode === 'detect' || mode === 'ok') return 'FORM';

  function setVal(el, v) {
    try { el.focus(); } catch (e) {}
    var proto = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
    try { proto.set.call(el, v); } catch (e) { el.value = v; }
    ['input', 'change', 'keyup', 'blur'].forEach(function (n) {
      try { el.dispatchEvent(new Event(n, { bubbles: true })); } catch (e) {}
    });
  }

  // Usuario: campo de texto cuyo nombre/id parezca de usuario; si no, el primer campo de texto visible.
  var inputs = doc.querySelectorAll('input');
  var user = null, firstText = null;
  for (var i = 0; i < inputs.length; i++) {
    var el = inputs[i], t = (el.getAttribute('type') || 'text').toLowerCase();
    if (pws.indexOf(el) >= 0) continue;
    if (t !== 'text' && t !== 'email' && t !== 'tel' && t !== '') continue;
    if (!firstText && visible(el)) firstText = el;
    if (!user && USER_RE.test(attrs(el)) && !PW_RE.test(attrs(el))) user = el;
  }
  user = user || firstText;
  if (user) setVal(user, u);
  pws.forEach(function (el) { setVal(el, p); });

  // Marcar "recordar" si existe
  var cbs = doc.querySelectorAll('input[type=checkbox]');
  for (var c = 0; c < cbs.length; c++) {
    if (/remem|record/i.test(attrs(cbs[c])) && !cbs[c].checked) { try { cbs[c].click(); } catch (e) {} }
  }

  var pw = pws[0];
  var form = pw.form || (pw.closest ? pw.closest('form') : null);

  function findButton() {
    var cand = doc.querySelectorAll('button,input[type=submit],input[type=button],input[type=image],a,div,span,li,img');
    var best = null, bestScore = 0;
    for (var j = 0; j < cand.length; j++) {
      var e = cand[j];
      if (!visible(e)) continue;
      if (e.contains && e.contains(pw)) continue; // contenedores del formulario no
      var own = txt(e.innerText || e.textContent).trim();
      if (own.length > 20) own = '';
      var meta = txt(e.value) + ' ' + txt(e.id) + ' ' + (e.getAttribute('class') || '') + ' ' +
                 (e.getAttribute('onclick') || '') + ' ' + (e.getAttribute('src') || '');
      var score = 0;
      if (BTN_RE.test(own)) score += 3;
      if (BTN_RE.test(meta)) score += 2;
      if (score === 0) continue;
      var tag = e.tagName;
      if (tag === 'BUTTON' || tag === 'INPUT') score += 3;
      else if (tag === 'A') score += 2;
      if (e.getAttribute('onclick')) score += 1;
      if (score > bestScore) { best = e; bestScore = score; }
    }
    if (!best && form) best = form.querySelector('[type=submit],[type=image]');
    return best;
  }

  function pressEnter(el) {
    ['keydown', 'keypress', 'keyup'].forEach(function (n) {
      try {
        el.dispatchEvent(new KeyboardEvent(n, { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
      } catch (e) {}
    });
  }

  setTimeout(function () {
    var btn = findButton();
    if (mode === 1) {
      if (btn) btn.click(); else pressEnter(pw);
    } else if (mode === 2) {
      pressEnter(pw);
      if (btn) setTimeout(function () { btn.click(); }, 300);
    } else {
      var w = doc.defaultView || window;
      var fns = ['login', 'doLogin', 'userLogin', 'submitLogin', 'loginSubmit', 'checkLogin', 'submitForm'];
      for (var x = 0; x < fns.length; x++) {
        if (typeof w[fns[x]] === 'function') { try { w[fns[x]](); return; } catch (e) {} }
      }
      if (form) { if (form.requestSubmit) form.requestSubmit(); else form.submit(); }
      else if (btn) btn.click();
    }
  }, 500);
  return 'SUBMITTED';
}
