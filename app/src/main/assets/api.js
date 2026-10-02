(function () {
  "use strict";

  var SPEC_PATH = "api/v1/openapi.json";
  var CONDITIONAL_APPROVAL_PATHS = Object.freeze({
    "/api/v1/config": true,
    "/api/v1/config/export": true,
    "/api/v1/config/import": true,
    "/api/v1/restore": true,
    "/api/v1/action": true
  });

  function t(key, fallback, values) {
    if (window.HaI18n && typeof window.HaI18n.t === "function") {
      try { return window.HaI18n.t(key, fallback, values); } catch (_) { /* English remains usable. */ }
    }
    return String(fallback).replace(/\{([A-Za-z][A-Za-z0-9_]*)\}/g, function (placeholder, name) {
      return values && Object.prototype.hasOwnProperty.call(values, name) ? String(values[name]) : placeholder;
    });
  }

  function text(id, key, fallback, values) {
    var node = document.getElementById(id);
    if (node) node.textContent = t(key, fallback, values);
  }

  function el(tag, className, value, language) {
    var node = document.createElement(tag);
    if (className) node.className = className;
    if (value != null) node.textContent = String(value);
    if (language) node.lang = language;
    return node;
  }

  function appendOpaqueAtMarker(parent, localized, marker, opaque) {
    var parts = String(localized).split(marker);
    parts.forEach(function (part, index) {
      if (index) parent.appendChild(el("span", null, opaque, "und"));
      if (part) parent.appendChild(document.createTextNode(part));
    });
  }

  function hydrateChrome() {
    text("api-back", "api.header.back_to_panel", "← back to panel");
    text("api-intro-live", "api.intro.live", "Live, on-panel API explorer.");

    var importNode = document.getElementById("api-intro-import");
    if (importNode) {
      var marker = "__OPENAPI_LINK__";
      var translated = t(
        "api.intro.import",
        "Import openapi.json into Swagger/Postman for fleet tooling."
      ).replace("openapi.json", marker);
      var parts = translated.split(marker);
      importNode.appendChild(document.createTextNode(parts[0] || ""));
      var link = el("a", null, "openapi.json");
      link.href = SPEC_PATH;
      link.target = "_blank";
      link.rel = "noopener";
      importNode.appendChild(link);
      if (parts.length > 1) importNode.appendChild(document.createTextNode(parts.slice(1).join(marker)));
    }

    text("api-intro-network", "api.intro.network", "No auth; LAN only.");
    text(
      "hardened-approval-description",
      "configure.hardened.action_approval",
      "Requires physical on-panel approval for this action when Hardened mode is enabled."
    );
    text(
      "hardened-approval-conditional-description",
      "api.approval.conditional",
      "Some request values require physical on-panel approval when Hardened mode is enabled; other values may be ordinary or unavailable."
    );
  }

  function requestBody(operation) {
    var content = operation.requestBody && operation.requestBody.content;
    if (!content) return null;
    var preferred = [
      "application/x-www-form-urlencoded", "application/json", "application/yaml",
      "text/yaml", "text/plain", "application/octet-stream"
    ];
    for (var i = 0; i < preferred.length; i += 1) {
      if (content[preferred[i]]) return { type: preferred[i], schema: content[preferred[i]].schema || {} };
    }
    var types = Object.keys(content);
    return types.length ? { type: types[0], schema: content[types[0]].schema || {} } : null;
  }

  function addInput(body, labelText, parameter) {
    var label = el("label", null, labelText);
    if (parameter.description) {
      var description = el("span", null, " — " + parameter.description, "en");
      description.style.color = "#667";
      description.style.fontWeight = "400";
      label.appendChild(description);
    }
    body.appendChild(label);

    var input;
    if (parameter.enum) {
      input = el("select");
      parameter.enum.forEach(function (value) {
        var option = el("option", null, value);
        option.value = value;
        input.appendChild(option);
      });
    } else {
      input = el("input");
      input.type = parameter.format === "password" ? "password" :
        (parameter.type === "number" || parameter.type === "integer" ? "number" : "text");
      input.step = "any";
    }
    body.appendChild(input);
    return input;
  }

  function render(spec) {
    var paths = spec.paths;
    var groups = [];
    var groupIndex = Object.create(null);
    Object.keys(paths).forEach(function (path) {
      Object.keys(paths[path]).forEach(function (method) {
        var operation = paths[path][method];
        var tag = operation.tags && operation.tags[0];
        var groupKey = tag ? "spec:" + tag : "fallback";
        var index = groupIndex[groupKey];
        if (index == null) {
          index = groups.length;
          groupIndex[groupKey] = index;
          groups.push({ label: tag || t("api.group.other", "other"), language: tag ? "en" : null, entries: [] });
        }
        groups[index].entries.push({ path: path, method: method, operation: operation });
      });
    });
    var root = document.getElementById("root");
    groups.forEach(function (group) {
      root.appendChild(el("h2", null, group.label, group.language));
      group.entries.forEach(function (entry) {
        root.appendChild(endpoint(entry.path, entry.method, entry.operation));
      });
    });
    text(
      "api-approval-key",
      "shell.hardened.key",
      "Shielded actions need physical approval on this panel in Hardened mode; they cannot be approved remotely."
    );
  }

  function endpoint(path, method, operation) {
    var details = el("details", "ep");
    var summary = el("summary");
    summary.appendChild(el("span", "m " + method, method.toUpperCase()));
    summary.appendChild(el("span", "path", path));
    summary.appendChild(el("span", "sum", operation.summary || "", "en"));
    details.appendChild(summary);

    var body = el("div", "body");
    var fields = {};
    var parameters = [];
    var request = requestBody(operation);
    (operation.parameters || []).forEach(function (parameter) {
      var schema = parameter.schema || {};
      parameters.push({
        spec: parameter,
        input: addInput(body, parameter.name + (parameter.required ? " *" : "") + " (" + parameter.in + ")", {
          type: schema.type,
          format: schema.format,
          enum: schema.enum,
          description: parameter.description
        })
      });
    });
    if (request && request.type === "application/x-www-form-urlencoded" && request.schema.properties) {
      Object.keys(request.schema.properties).forEach(function (key) {
        var parameter = request.schema.properties[key];
        var required = (request.schema.required || []).indexOf(key) >= 0;
        fields[key] = addInput(body, key + (required ? " *" : ""), parameter);
      });
    } else if (request) {
      body.appendChild(el(
        "label",
        null,
        t("api.request.body", "request body ({type})", { type: request.type })
      ));
      if (request.type === "application/octet-stream") {
        var file = el("input");
        file.type = "file";
        fields.__body = file;
        body.appendChild(file);
      } else {
        var textarea = el("textarea");
        if (request.type === "application/json" && request.schema.type === "object") textarea.value = "{}";
        fields.__body = textarea;
        body.appendChild(textarea);
      }
    }

    var upperMethod = method.toUpperCase();
    var button = el("button", null, t("api.action.send", "Send {method}", { method: upperMethod }));
    var approvalKind = JSON.stringify(operation.responses || {}).indexOf("ApprovalRequired") >= 0 ? "required" : null;
    if (approvalKind && CONDITIONAL_APPROVAL_PATHS[path]) approvalKind = "conditional";
    if (approvalKind) {
      var conditional = approvalKind === "conditional";
      button.setAttribute("data-hardened-approval", conditional ? "conditional" : "");
      button.setAttribute(
        "aria-describedby",
        conditional ? "hardened-approval-conditional-description" : "hardened-approval-description"
      );
      button.title = conditional ? t(
        "api.approval.conditional",
        "Some request values require physical on-panel approval when Hardened mode is enabled; other values may be ordinary or unavailable."
      ) : t(
        "configure.hardened.action_approval",
        "Requires physical on-panel approval for this action when Hardened mode is enabled."
      );
    }

    var status = el("div", "st");
    var output = el("pre", null, null, "und");
    output.style.display = "none";
    body.appendChild(button);
    body.appendChild(status);
    body.appendChild(output);
    button.onclick = function () {
      send(path, method, fields, parameters, request, status, output, button);
    };
    details.appendChild(body);
    return details;
  }

  function send(path, method, fields, parameters, request, status, output, button) {
    button.disabled = true;
    status.textContent = "…";
    output.style.display = "none";
    var options = { method: method.toUpperCase(), headers: {} };
    var query = [];
    parameters.forEach(function (parameter) {
      var value = parameter.input.value;
      if (value === "") return;
      var where = parameter.spec.in;
      var name = parameter.spec.name;
      if (where === "path") path = path.replace("{" + name + "}", encodeURIComponent(value));
      else if (where === "query") query.push(encodeURIComponent(name) + "=" + encodeURIComponent(value));
      else if (where === "header") options.headers[name] = value;
    });
    if (query.length) path += (path.indexOf("?") >= 0 ? "&" : "?") + query.join("&");
    if (request && request.type === "application/x-www-form-urlencoded") {
      var params = [];
      Object.keys(fields).forEach(function (key) {
        var value = fields[key].value;
        if (value !== "") params.push(encodeURIComponent(key) + "=" + encodeURIComponent(value));
      });
      if (params.length) options.body = params.join("&");
      options.headers["Content-Type"] = request.type;
    } else if (request && fields.__body) {
      if (request.type === "application/octet-stream") {
        if (fields.__body.files && fields.__body.files[0]) options.body = fields.__body.files[0];
      } else options.body = fields.__body.value;
      options.headers["Content-Type"] = request.type;
    }

    var started = Date.now();
    fetch(path, options).then(function (response) {
      return response.text().then(function (responseText) {
        status.textContent = "";
        var code = el("b", null, response.status);
        code.style.color = response.ok ? "#5ad18a" : "#d9534f";
        status.appendChild(code);
        status.appendChild(document.createTextNode(
          " " + (response.headers.get("content-type") || "") + " · " + (Date.now() - started) + " ms"
        ));
        var responseBody = responseText;
        try { responseBody = JSON.stringify(JSON.parse(responseText), null, 2); } catch (_) { /* Keep exact body. */ }
        output.textContent = responseBody;
        output.style.display = "";
        button.disabled = false;
      });
    }).catch(function (error) {
      status.textContent = "";
      var label = el("b", null, t("api.status.error", "error"));
      label.style.color = "#d9534f";
      status.appendChild(label);
      status.appendChild(document.createTextNode(" "));
      status.appendChild(el("span", null, error, "und"));
      button.disabled = false;
    });
  }

  hydrateChrome();
  fetch(SPEC_PATH).then(function (response) { if (!response.ok) throw new Error("HTTP " + response.status); return response.json(); }).then(render).catch(function (error) {
    if (!document.querySelector("#root details")) window.HaI18n.pageFailure(t("api.error.load_spec", "Could not load {path}: {error}", { path: SPEC_PATH, error: String(error) }));
  });
}());
