/*
   Renders markdown posts on show_topic.html to html client-side.
   I want to eventually do this server-side, but I can't find a good
   java/clj markdown library that replicates markdown.js.
*/

$(function() {
  $('.post-body').each(function() {
    $(this).html(markdown.toHTML($(this).text()));
  });
});
