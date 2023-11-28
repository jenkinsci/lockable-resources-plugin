// SPDX-License-Identifier: MIT
// Copyright (c) 2020, Tobias Gruetzmacher

function find_resource_name(element) {
  var row = element.closest('tr');
  var resourceName = row.getAttribute('data-resource-name');
  return resourceName;
}

function resource_action(button, action) {
  // TODO: Migrate to form:link after Jenkins 2.233 (for button-styled links)
  var form = document.createElement('form');
  var resourceName = find_resource_name(button);
  form.setAttribute('method', 'POST');
  form.setAttribute('action', action + "?resource=" + encodeURIComponent(resourceName));
  crumb.appendToForm(form);
  document.body.appendChild(form);
  form.submit();
  notificationBar.show(action + ' was successfully performed on ' + resourceName, notificationBar.SUCCESS);
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

    jQuery("#lockable-resources").DataTable().columns().every( function () {
        if (!this.visible())
            indexes.push(this.index())
    });

    return indexes;
}

jQuery(document).ready(function() {
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
} );
