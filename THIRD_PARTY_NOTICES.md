# Third-party notices

OmniCode Agent includes [Apache PDFBox](https://pdfbox.apache.org/) for bounded local text extraction from user-selected PDF research papers.

- Component: Apache PDFBox 3.0.8
- Copyright: The Apache Software Foundation
- License: Apache License 2.0
- Source: https://pdfbox.apache.org/

PDFBox dependencies included by the build retain their respective notices and licenses in their distributed JAR metadata.

OmniCode includes network JDBC drivers so users can connect to supported remote databases without
installing a local database server or manually supplying driver JARs. Database passwords are kept
in JetBrains Password Safe and queries remain subject to explicit approval and read-only limits.

- Component: PostgreSQL JDBC Driver 42.7.13
- Copyright: PostgreSQL Global Development Group
- License: BSD 2-Clause License
- Source: https://github.com/pgjdbc/pgjdbc

- Component: MariaDB Connector/J 3.5.10
- Copyright: MariaDB Corporation Ab
- License: GNU Lesser General Public License v2.1 or later
- Source: https://github.com/mariadb-corporation/mariadb-connector-j

OmniCode 3.0's information architecture and selected MIT-licensed interaction patterns are based
on the pinned JetBrains CCGUI source revision below. OmniCode keeps its own name, branding,
runtime security boundaries, and implementation.

- Component: JetBrains CCGUI v0.5.4 (revision 42f9786624eb313ba8ad5cd00ba0baae1e01143f)
- Copyright: © 2026 zhukunpenglinyutong（朱昆鹏）
- License: MIT License
- Source: https://github.com/zhukunpenglinyutong/jetbrains-cc-gui/tree/42f9786624eb313ba8ad5cd00ba0baae1e01143f
- License copy: `licenses/CCGUI-MIT.txt`

The local WebView renderer includes the following JavaScript libraries for fenced-code,
mathematics, and diagram rendering. No library is loaded from a CDN at runtime.

- Component: highlight.js 11.11.1 — BSD 3-Clause — https://github.com/highlightjs/highlight.js
- Component: KaTeX 0.16.22 — MIT — https://github.com/KaTeX/KaTeX
- Component: Mermaid 11.17.2 — MIT — https://github.com/mermaid-js/mermaid
- Components: remark-math 6.0.0 and rehype-katex 7.0.1 — MIT — https://github.com/remarkjs/remark-math
