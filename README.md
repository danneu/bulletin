# bulletin

A bulletin board service (like [proboards](http://www.proboards.com/)) written in Clojure.

Live demo: [www.fed.nu](http://www.fed.nu) (It's very rough)

## Setting up for local development

Install leiningen so that you have the `lein` command available in your console.

Use environment variables to configure bulletin. A nice way to do this is to create `.lein-env` in the root directory:

    {:database-url "postgres://<dbuser>:<dbpassword>@localhost:5432/bulletin"}

You can also provide `:port <Number>`, but the default port will be 3000.

Now boot the server:

    lein ring server-headless

To force the server to create the database tables and then populate them with seed data, visit `http://localhost:3000/reset-db`.

The website should now be running on `http://localhost:3000`.

## Misc

- Using bootstrap v3.2.0
- Using bootstrap-markdown v2.6.0 (jQuery v2.1.1) Deps on bootstrap 3.1.1 but I'll use 3.2.0 for convenience for now

## TODO

- Add more `ON DELETE CASCADE`
- Implement authorization and protect routes with it
- Add CSRF protection
- Add tests for authorization rules at the very least
- Recaptcha community creation
- Flood protection
- Users should only be able to  edit their posts for a certain duration
- Find java/clj markdown lib that's compatible enough with markdown.js that also escapes user html like markdown.js does. So far no good.
- For now I'm disabling buttons that the current user is unauthorized to use so that I can quickly reason about my progress and the look/feel. But I will eventually avoid rendering the buttons completely instead of just greying them out.
- I'm used to using Hiccup for templating which makes it trivial to share my cancan.clj between routes and templates. However, this is my first time using Selmer and so far my approach of shoehorning `can-*-?` keys into random objects has been ugly and feels so ad-hoc.
- I really need to document things like what `*current-user*` actually looks like. What kind of things can get assoc'd to it. Etc.
- Reuse views/community layout on www homepage
- Add profiling per route
- Add logging

And much much more...
