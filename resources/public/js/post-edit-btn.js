$(function() {
  $('.post-edit-btn').click(function() {
    $post_edit_btn = $(this);
    $post_edit_btn.addClass('disabled');
    var post_id = $(this).attr('post-id');
    // $('#post-' + post_id + ' .post-editor').markdown({
    //   savabe: true
    // });
    // $('#post-' + post_id + ' .post-body').html('<p>lol</p>');
    var post_url = '/api/posts/' + post_id;
    var $post_body = $('#post-' + post_id + ' .post-body');
    var prev_body = $post_body.html();
    var $spinner = $('<span><img src="/img/spinner.gif"> Loading edit form...</span>');
    $post_body.html($spinner);
    $cancel_btn = $('<button style="margin-left: 5px;" class="btn btn-default post-edit-cancel-btn">Cancel</button>');

    $.ajax({
      url: post_url,
      dataType: 'html',
      success: function(post_text) {
        console.log(post_text);
        var $post_editor = $('<div class="post-editor"></div>');
        $spinner.remove();
        $post_body.append($post_editor);
        $post_editor.markdown({
          resize: 'vertical',
          savable: true,
          onSave: function(e) {
            //
            $success_btn = $post_body.find('.btn-success');
            $success_btn.html('Saving...');
            $success_btn.attr('disabled', true);
            $post_body.find('.btn').attr('disabled', true);

            var text_to_save = e.getContent();
            $.ajax({
              url: post_url,
              dataType: 'json',
              type: 'POST',
              headers: { 'X-HTTP-Method-Override': 'PUT' },
              data: { text: text_to_save },
              success: function(updated_post) {
                console.log('updated post: ' + JSON.stringify(updated_post, null, '  '))
                $post_body.html(markdown.toHTML(updated_post.text));
                $post_edit_btn.removeClass('disabled');
                // Set the post's .edited-marker to ' edited'
                var $edited_marker = $('#post-' + post_id + ' .edited-marker')
                $edited_marker.html(' edited');
                console.log($edited_marker)
              }
            })
          }
        });

        // Gotta set the text before the .markdown() call or else
        // .markdown() escapes html entities.
        $post_body.find('textarea').val(post_text)

        $('#post-' + post_id + ' .md-footer').append(
          $cancel_btn
        );
        $($cancel_btn).click(function() {
          $post_body.html(prev_body);
          $post_edit_btn.removeClass('disabled');
        });
      }
    });

    return false;
  });
});
