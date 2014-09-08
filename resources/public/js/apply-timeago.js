/*
  This applies timeago(_) and replaces the contents of any .timeago element.

  Load this after jquery.timeago.js
*/

jQuery.timeago.settings.strings = {
  prefixAgo: null,
  prefixFromNow: null,
  suffixAgo: "ago",
  suffixFromNow: "from now",
  seconds: "1 min",
  minute: "1 min",
  minutes: "%d mins",
  hour: "1 hour",
  hours: "%d hours",
  day: "1 day",
  days: "%d days",
  month: "1 month",
  months: "%d months",
  year: "1 year",
  years: "%d years",
  wordSeparator: " ",
  numbers: []
};

$(function() {
  $('.timeago').timeago();  // <-- Why doesn't this work
  // $('.timeago').each(function() {
  //   $(this).text($.timeago($(this).text()))
  // });
});
