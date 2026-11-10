"""mkdocs hook: a link from docs/ to a file of the repository (../runtime/..., ../amie/README.md) points at that file
on GitHub, since the site holds only the pages of docs/."""
import re

REPO = "https://github.com/samging/amieCli/blob/main/"
LINK = re.compile(r"\]\(\.\./([^)\s]+)\)")


def on_page_markdown(markdown, **kwargs):
    return LINK.sub(lambda m: "](" + REPO + m.group(1) + ")", markdown)
