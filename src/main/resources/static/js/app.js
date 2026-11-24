/* Teagather — app.js
   - 확인 모달 : data-confirm / data-confirm-body / data-confirm-ok / data-danger (window.confirm 대신)
   - 토스트 : <template data-toast> (data-toast="error" 면 빨강)
   - 중복 제출 막기 : form[data-once]
   - 수량 : [data-qty] 안의 − + 버튼
   - 좁은 화면 표 카드 : thead 글자로 td 의 data-label 을 채운다
   - 분석 중이면 [data-refresh] 초마다 새로고침 */
(function () {
    'use strict';

    // ---------- 토스트 ----------
    var box = document.createElement('div');
    box.className = 'toasts';
    box.setAttribute('role', 'status');
    box.setAttribute('aria-live', 'polite');
    document.body.appendChild(box);
    function toast(text, error) {
        var t = document.createElement('div');
        t.className = 'toast' + (error ? ' error' : '');
        t.textContent = text;
        box.appendChild(t);
        setTimeout(function () { t.style.opacity = '0'; t.style.transition = 'opacity .3s'; }, 4200);
        setTimeout(function () { t.remove(); }, 4600);
    }
    document.querySelectorAll('template[data-toast]').forEach(function (tpl) {
        var text = (tpl.textContent || tpl.innerHTML || '').trim();
        if (text) toast(text, tpl.getAttribute('data-toast') === 'error');
    });

    // ---------- 확인 모달 ----------
    function confirmModal(opts, onOk) {
        var back = document.createElement('div');
        back.className = 'modal-back';
        back.innerHTML = '<div class="modal" role="dialog" aria-modal="true" aria-labelledby="m-title">' +
            '<h3 id="m-title"></h3><p></p><div class="row"><button type="button" class="btn" data-no>취소</button>' +
            '<button type="button" class="btn" data-yes></button></div></div>';
        back.querySelector('h3').textContent = opts.title;
        var p = back.querySelector('p');
        if (opts.body) p.textContent = opts.body; else p.remove();
        var yes = back.querySelector('[data-yes]');
        yes.textContent = opts.ok || '확인';
        yes.classList.add(opts.danger ? 'danger' : 'primary');
        var prev = document.activeElement;
        function close() { back.remove(); document.removeEventListener('keydown', onKey); if (prev) prev.focus(); }
        function onKey(e) {
            if (e.key === 'Escape') close();
            if (e.key === 'Tab') {   // 모달 안에서만 Tab
                var f = back.querySelectorAll('button');
                if (e.shiftKey && document.activeElement === f[0]) { e.preventDefault(); f[f.length - 1].focus(); }
                else if (!e.shiftKey && document.activeElement === f[f.length - 1]) { e.preventDefault(); f[0].focus(); }
            }
        }
        back.addEventListener('click', function (e) { if (e.target === back) close(); });
        back.querySelector('[data-no]').addEventListener('click', close);
        yes.addEventListener('click', function () { close(); onOk(); });
        document.addEventListener('keydown', onKey);
        document.body.appendChild(back);
        yes.focus();
    }
    document.addEventListener('click', function (e) {
        var el = e.target.closest('[data-confirm]');
        if (!el || el.tagName === 'FORM' || el.dataset.confirmed) return;
        e.preventDefault();
        confirmModal({ title: el.dataset.confirm, body: el.dataset.confirmBody, ok: el.dataset.confirmOk, danger: el.hasAttribute('data-danger') },
            function () {
                el.dataset.confirmed = '1';
                if (el.form && el.type === 'submit') {
                    if (el.name) {   // 누른 버튼의 name/value 도 보낸다
                        var h = document.createElement('input');
                        h.type = 'hidden'; h.name = el.name; h.value = el.value;
                        el.form.appendChild(h);
                    }
                    el.form.requestSubmit ? el.form.requestSubmit() : el.form.submit();
                } else if (el.href) {
                    location.href = el.href;
                }
            });
    });

    // ---------- 중복 제출 막기 ----------
    document.addEventListener('submit', function (e) {
        var f = e.target;
        if (!f.hasAttribute('data-once')) return;
        if (f.dataset.sent) { e.preventDefault(); return; }
        f.dataset.sent = '1';
        setTimeout(function () {
            f.querySelectorAll('button[type=submit], button:not([type])').forEach(function (b) { b.disabled = true; });
        }, 0);
    });

    // ---------- 수량 ----------
    document.querySelectorAll('[data-qty]').forEach(function (q) {
        var input = q.querySelector('input');
        q.addEventListener('click', function (e) {
            var b = e.target.closest('button[data-step]');
            if (!b) return;
            e.preventDefault();
            var v = Math.max(1, Math.min(20, (parseInt(input.value, 10) || 1) + parseInt(b.dataset.step, 10)));
            input.value = v;
            input.dispatchEvent(new Event('change', { bubbles: true }));
        });
    });
    document.querySelectorAll('[data-auto-submit]').forEach(function (el) {
        el.addEventListener('change', function () { el.form.submit(); });
    });

    // ---------- 메뉴 상세 : 옵션을 고르면 가격 다시 계산 ----------
    var calc = document.querySelector('[data-price-calc]');
    if (calc) {
        var base = parseInt(calc.dataset.base, 10), disc = parseInt(calc.dataset.discount || '0', 10);
        var out = calc.querySelector('[data-total]');
        var update = function () {
            var extra = 0;
            calc.querySelectorAll('input[data-extra]:checked').forEach(function (i) { extra += parseInt(i.dataset.extra, 10); });
            var qty = parseInt((calc.querySelector('input[name=quantity]') || {}).value, 10) || 1;
            out.textContent = ((base + extra - disc) * qty).toLocaleString('ko-KR') + '원';
        };
        calc.addEventListener('change', update);
        update();
    }

    // ---------- 표 카드 : data-label ----------
    document.querySelectorAll('.table-box table.t').forEach(function (t) {
        var heads = Array.prototype.map.call(t.querySelectorAll('thead th'), function (th) { return th.textContent.trim(); });
        t.querySelectorAll('tbody tr').forEach(function (tr) {
            Array.prototype.forEach.call(tr.children, function (td, i) {
                if (!td.hasAttribute('data-label')) td.setAttribute('data-label', heads[i] || '');
            });
        });
    });

    // ---------- 분석 중 새로고침 ----------
    var r = document.querySelector('[data-refresh]');
    if (r) setTimeout(function () { location.reload(); }, (parseInt(r.dataset.refresh, 10) || 5) * 1000);
})();
