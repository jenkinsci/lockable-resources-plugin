// SPDX-License-Identifier: MIT
// Copyright (c) 2020, Tobias Gruetzmacher

function changeQueueOrder(button, queueId) {

  dialog
    .prompt(i18n("queue-title", queueId), {
      message: i18n("queue-message", queueId),
      minWidth: "450px",
      maxWidth: "600px"
    })
    .then(
      (newPosition) => {
        fetch("changeQueueOrder?id=" + queueId + "&index=" + newPosition,
          {
            method: "post",
            headers: crumb.wrap({}),
          },
        ).then((rsp) => {
          showResponse(rsp, i18n("queue-on-success", queueId), i18n("queue-on-fail", queueId));
        });
      }
    );
}

function showResponse(rsp, okText, errorText) {
  rsp.text().then((responseText) => {
    if (!rsp.ok) {
      let parsed = new DOMParser().parseFromString(responseText, 'text/html');
      dialog.alert(errorText, {
        message: parsed.title
      });
    } else {
      notificationBar.show(okText, notificationBar.SUCCESS);
      window.location.reload();
    }
  });
}

function find_resource_name(element) {
  var row = element.closest('tr');
  var resourceName = row.getAttribute('data-resource-name');
  return resourceName;
}


function resource_action(button, action) {
  var resourceName = find_resource_name(button);
  fetch(action + "?resource=" + encodeURIComponent(resourceName),
    {
      method: "post",
      headers: crumb.wrap({}),
    },
  ).then((rsp) => {
    showResponse(rsp, i18n("action-on-success", action, resourceName), i18n("action-on-fail", action, resourceName));
    if (!rsp.ok) {
      window.location.reload();
    }
  });
}

function replaceNote(element, resourceName) {
  var d = document.getElementById("note-" + resourceName);
  d.innerHTML = "<div class='spinner-right' style='flex-grow: 1;'>loading...</div>";
  fetch("noteForm", {
    method: "post",
    headers: crumb.wrap({
      "Content-Type": "application/x-www-form-urlencoded",
    }),
    body: new URLSearchParams({
      resource: resourceName,
    }),
  }).then((rsp) => {
    rsp.text().then((responseText) => {
      d.innerHTML = responseText;
      evalInnerHtmlScripts(responseText, function () {
        Behaviour.applySubtree(d);
        d.getElementsByTagName("TEXTAREA")[0].focus();
      });
      layoutUpdateCallback.call();
    });
  });
  return false;
}

function format(d) {
  // `d` is the original data object for the row
  // show all the hidden columns in the child row
  var hiddenRows = getHiddenColumns();
  return hiddenRows.map(i => d[i]).join("<br>");
}

function getHiddenColumns() {
  // returns the indexes of all hidden rows
  var indexes = new Array();

  jQuery("#lockable-resources").DataTable().columns().every(function () {
    if (!this.visible())
      indexes.push(this.index())
  });

  return indexes;
}

function i18n(messageId, arg0, arg1) {
  return document.querySelector("#i18n").getAttribute("data-" + messageId).replace("{0}", arg0).replace("{1}", arg1);
}

jQuery(document).ready(function () {

  // Add event listener to store last opened tab
  var triggerTabList = document.querySelectorAll('button[data-bs-toggle="tab"]')
  triggerTabList.forEach(tabEl => {
    tabEl.addEventListener('show.bs.tab', function (event) {
      localStorage.setItem('lockable-resources-active-tab', event.target.dataset.bsTarget);
    })
  });

  // activate last opened tab
  var activeTab;
  try {
    activeTab = localStorage.getItem('lockable-resources-active-tab');
  } catch (e) {
    console.log("localstorage is not allowed");
  }
  if (activeTab) {
    const triggerEL = document.querySelector(`button[data-bs-target="${activeTab}"]`);
    if (triggerEL) {
      bootstrap.Tab.getOrCreateInstance(triggerEL).show()
    }
  }

  // Add event listener for opening and closing details
  jQuery('#lockable-resources tbody').on('click', 'td.dt-control', function () {
    var tr = jQuery(this).closest('tr');
    var row = jQuery("#lockable-resources").DataTable().row(tr);

    // child row example taken from https://datatables.net/examples/api/row_details.html
    if (row.child.isShown()) {
      // This row is already open - close it
      row.child.hide();
      tr.removeClass('shown');
    } else {
      // Open this row
      row.child(format(row.data())).show();
      tr.addClass('shown');
    }
  });
});


