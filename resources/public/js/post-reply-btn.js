var m;
$(function() {
  $('.post-reply-btn').click(function(e) {
    $reply_btn = $(this);
    $reply_btn.addClass('disabled').html('Loading...');
    var post_id = $reply_btn.attr('post-id');
    // Get this post's text
    //var post_text = $('#post-' + post_id).find('.post-body').text()
    var post_url = '/api/posts/' + post_id;
    $.get(post_url, function(markup) {
      console.log('returned')
      $reply_btn.removeClass('disabled').html('Reply');
      // var quote_markup = _.map(markup.split('\n'), function(line) { return '> ' + line }).join('\n')
      var quote_markup = markup.split('\n').map(function(line) {
        return '> ' + line;
      }).join('\n')
      var $reply_textarea = $('#reply-form textarea');
      var prev_content = $('textarea').val().trim();
      // Only add \n\n padding if there was already content in textarea
      var padding = (function() {
        if (prev_content.length === 0) {
          return '';
        }
        return '\n\n'
      })();
      $reply_textarea.focus().val('').val(prev_content + padding + quote_markup + '\n\n');
      $reply_textarea.scrollTop($reply_textarea[0].scrollHeight);

      $('html, body').animate({
        scrollTop: $reply_textarea.offset().top
      }, 100);

    });

    return false;
  });
});
