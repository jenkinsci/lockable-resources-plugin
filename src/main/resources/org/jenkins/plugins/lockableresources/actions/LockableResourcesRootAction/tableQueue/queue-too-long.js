document.addEventListener("DOMContentLoaded", function() {
  notificationBar.show(
    document.querySelector(".lockable-resources-queue-too-long-message").dataset.warningMessage,
    notificationBar.WARNING);
});
