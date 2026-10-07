// Shared explicit repair/acknowledgement interaction for Information, Configure, and Install banners.
(function () {
  "use strict";
  var projection = {};
  try {
    var source = document.getElementById('ha-i18n'); projection = source ? JSON.parse(source.textContent || '{}') : {};
    if (!projection || typeof projection !== 'object' || Array.isArray(projection)) projection = {};
  } catch (_) { projection = {}; }
  var locale = window.HaI18n && typeof window.HaI18n.locale === 'string' ? window.HaI18n.locale : (document.documentElement && document.documentElement.lang || 'en');
  var languages = projection.languages && typeof projection.languages === 'object' && !Array.isArray(projection.languages) ? projection.languages : {};
  var t = window.HaI18n.t;
  function localized(key) { return locale === 'en' || languages[key] === locale; }
  function repairMessage(body) {
    if (body && body.error === 'approval-required') {
      return t('runtime.power_safety.repair.approval', body.message || 'Approve this request physically on the panel, then tap Repair power safety again.');
    }
    if (body && body.status === 'repaired') return t('runtime.power_safety.repair.repaired', body.message || 'Power safety repaired and verified.') + ' ' + t('runtime.power_safety.repair.refreshing', 'Refreshing…');
    if (body && body.status === 'partial') return t('runtime.power_safety.repair.partial', body.message || 'Power safety repair was partial. No reboot was attempted.');
    return t('runtime.power_safety.repair.failed_no_reboot', body && body.message || 'Power safety repair failed; no reboot was attempted.');
  }

  function parseResponse(response) {
    return response.text().then(function (text) {
      var body = {};
      try { body = text ? JSON.parse(text) : {}; } catch (_) { body = { message: text }; }
      return { response: response, body: body };
    });
  }

  function offerAcknowledgement(form, body) {
    var powerSafety = body && body.power_safety;
    var fingerprint = powerSafety && powerSafety.acknowledgement_fingerprint;
    if (!powerSafety || !powerSafety.acknowledge_available || !fingerprint) return false;
    form.removeAttribute('data-power-safety-repair');
    form.setAttribute('data-power-safety-acknowledge', '');
    form.action = 'api/v1/power-safety/acknowledge';
    var hidden = document.createElement('input');
    hidden.type = 'hidden';
    hidden.name = 'fingerprint';
    hidden.value = fingerprint;
    form.insertBefore(hidden, form.firstChild);
    var button = form.querySelector('button[type="submit"]');
    if (button) {
      button.disabled = false;
      button.textContent = t('runtime.power_safety.button.hide', 'Hide this caution');
      button.setAttribute('data-hardened-approval', '');
      button.title = t('runtime.power_safety.button.hide_title', 'Hide this unchanged caution in panel web pages; Hardened mode requires physical approval');
    }
    var result = form.querySelector('.power-safety-repair-result');
    if (result) result.className = 'power-safety-acknowledge-result';
    return true;
  }

  document.addEventListener('submit', function (event) {
    var form = event.target;
    if (!form || !form.matches) return;
    if (form.matches('form[data-power-safety-acknowledge]')) {
      event.preventDefault();
      var acknowledgeButton = form.querySelector('button[type="submit"]');
      var acknowledgeResult = form.querySelector('.power-safety-acknowledge-result');
      if (acknowledgeButton) acknowledgeButton.disabled = true;
      if (acknowledgeResult) acknowledgeResult.textContent = t('runtime.power_safety.ack.saving', 'Saving acknowledgement…');
      var encoded = new URLSearchParams(new FormData(form)).toString();
      fetch(form.action, {
        method: 'POST',
        headers: { 'Accept': 'application/json', 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
        body: encoded
      })
        .then(parseResponse)
        .then(function (reply) {
          var body = reply.body;
          if (acknowledgeResult) acknowledgeResult.textContent = body.message && !localized('runtime.power_safety.ack.not_hidden') ? body.message : t('runtime.power_safety.ack.not_hidden', body.message || 'The caution was not hidden.');
          if (reply.response.ok && body.acknowledged) {
            var banner = form.closest('[data-power-safety-banner]');
            if (banner) banner.remove();
            return;
          }
          if (acknowledgeButton) acknowledgeButton.disabled = false;
        })
        .catch(function () {
          if (acknowledgeResult) acknowledgeResult.textContent = t('runtime.power_safety.ack.endpoint_failed', 'Could not save the acknowledgement; the caution remains visible.');
          if (acknowledgeButton) acknowledgeButton.disabled = false;
        });
      return;
    }
    if (!form.matches('form[data-power-safety-repair]')) return;
    event.preventDefault();
    var button = form.querySelector('button[type="submit"]');
    var result = form.querySelector('.power-safety-repair-result');
    if (button) button.disabled = true;
    if (result) result.textContent = t('runtime.power_safety.repair.applying', 'Applying and verifying…');
    fetch(form.action, { method: 'POST', headers: { 'Accept': 'application/json' } })
      .then(parseResponse)
      .then(function (reply) {
          var response = reply.response;
          var body = reply.body;
          if (result) result.textContent = repairMessage(body);
          if (response.status === 202 && body.error === 'approval-required') {
            if (button) button.disabled = false;
            return;
          }
          if (!response.ok || (body.status !== 'repaired' && body.status !== 'partial')) {
            if (button) button.disabled = false;
            return;
          }
          if (body.status === 'partial') {
            if (!offerAcknowledgement(form, body) && button) button.disabled = false;
            return;
          }
          setTimeout(function () { location.reload(); }, 1200);
      })
      .catch(function () {
        if (result) result.textContent = t('runtime.power_safety.repair.endpoint_failed', 'Could not reach the repair endpoint; no repair was confirmed.');
        if (button) button.disabled = false;
      })
      .finally(function () {
        if (button && result && bodyRefreshing(result.textContent) === false) button.disabled = false;
      });
  });
  function bodyRefreshing(text) {
    return String(text || '').indexOf(t('runtime.power_safety.repair.refreshing', 'Refreshing…')) >= 0;
  }
})();
