$(function() {
  $('.test-creds').click(function(e) {
    var text = $(e.target).text().split('/');
    var username = text[0];
    var password = text[1];
    $('#username').val(username)
    $('#password').val(password)
    $('#login [type=submit]').click()
    return false;
  });
});
