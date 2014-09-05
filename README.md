# bulletin

A bulletin board service like proboards except written in Clojure.

A recently started stubbing out an experimental forum system at [www.fed.nu](http://www.fed.nu) (to learn Koa), so that's what this is kinda gonna look like.

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
