<#--
  Email body: vehicle-reviewer-reminder. Plain text; the payload template
  templates/reviewer-reminder-email.json.ftl puts it into the Mailpit JSON.
-->
A vehicle registration submitted by ${firstName!""} ${lastName!""} has been
waiting for Transport Authority review. Please open the Tasks page in the
portal.

<#include "/email/_footer.ftl">
