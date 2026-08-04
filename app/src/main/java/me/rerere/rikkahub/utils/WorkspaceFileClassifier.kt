package me.rerere.rikkahub.utils

/**
 * 工作区文件类型分流：决定点击文件后走"应用内查看器"、".skill 安装询问"还是原有的外部打开。
 *
 * 仅凭文件名判断，不读内容；拿不准的一律返回 [Category.OTHER]，由调用方保留原行为。
 */
object WorkspaceFileClassifier {

    enum class Category {
        /** Markdown，用排版渲染预览。 */
        MARKDOWN,

        /** 代码/配置类文本，用等宽 + 语法高亮预览（[prismLanguage] 为 prism.js 语言名）。 */
        CODE,

        /** HTML 文档，用 WebView 渲染预览，同时保留源码查看。 */
        HTML,

        /** 普通文本，等宽纯文本预览。 */
        TEXT,

        /** 技能包（zip 压缩格式），点击后询问安装。 */
        SKILL_PACKAGE,

        /** DOCX 文档，用 docx-preview 在 WebView 内保留排版预览。 */
        DOCX,

        /** 其他类型，保留原有的"用其他应用打开"行为。 */
        OTHER,
    }

    data class Classification(
        val category: Category,
        /** [Category.CODE] 和 [Category.HTML] 使用的 prism.js 语言名；为 null 表示不做语法高亮。 */
        val prismLanguage: String? = null,
    )

    const val SKILL_PACKAGE_EXTENSION = "skill"

    /** 无扩展名但按文本处理的特殊文件名（忽略大小写比较）。 */
    private val SPECIAL_TEXT_FILE_NAMES = setOf(
        "makefile",
        "gnumakefile",
        "dockerfile",
        "containerfile",
        "procfile",
        "gemfile",
        "rakefile",
        "justfile",
        "vagrantfile",
        "cmakelists.txt",
        "build.gradle",
        "settings.gradle",
        ".gitignore",
        ".gitattributes",
        ".gitmodules",
        ".editorconfig",
        ".env",
        ".htaccess",
        ".npmrc",
        ".bashrc",
        ".zshrc",
        ".profile",
    )

    /**
     * 代码/配置类扩展名 → prism.js 语言名。
     * prism 未知语言时会静默不高亮，因此映射宁可保守。
     */
    private val CODE_EXTENSIONS = mapOf(
        // 脚本与配置
        "sh" to "bash",
        "bash" to "bash",
        "zsh" to "bash",
        "ksh" to "bash",
        "fish" to "bash",
        "py" to "python",
        "pyi" to "python",
        "rb" to "ruby",
        "php" to "php",
        "pl" to "perl",
        "lua" to "lua",
        "r" to "r",
        "jl" to "julia",
        "ps1" to "powershell",
        "psm1" to "powershell",
        "bat" to "batch",
        "cmd" to "batch",
        "awk" to "awk",
        "groovy" to "groovy",
        "dart" to "dart",
        "js" to "javascript",
        "mjs" to "javascript",
        "cjs" to "javascript",
        "jsx" to "jsx",
        "ts" to "typescript",
        "tsx" to "tsx",
        "vue" to "vue",
        "svelte" to "svelte",
        // JVM / 系统语言
        "kt" to "kotlin",
        "kts" to "kotlin",
        "java" to "java",
        "scala" to "scala",
        "c" to "c",
        "h" to "c",
        "cpp" to "cpp",
        "cc" to "cpp",
        "cxx" to "cpp",
        "hpp" to "cpp",
        "hh" to "cpp",
        "cs" to "csharp",
        "go" to "go",
        "rs" to "rust",
        "swift" to "swift",
        "m" to "objectivec",
        "mm" to "objectivec",
        "zig" to "zig",
        "nim" to "nim",
        "hs" to "haskell",
        "elm" to "elm",
        "ex" to "elixir",
        "exs" to "elixir",
        "erl" to "erlang",
        "clj" to "clojure",
        "f90" to "fortran",
        "f95" to "fortran",
        "asm" to "asm6502",
        "sol" to "solidity",
        // 数据与配置格式
        "json" to "json",
        "jsonc" to "json",
        "json5" to "json5",
        "yml" to "yaml",
        "yaml" to "yaml",
        "toml" to "toml",
        "xml" to "xml",
        "svg" to "svg",
        "css" to "css",
        "scss" to "scss",
        "sass" to "sass",
        "less" to "less",
        "sql" to "sql",
        "graphql" to "graphql",
        "gql" to "graphql",
        "proto" to "protobuf",
        "dockerfile" to "docker",
        "gradle" to "gradle",
        "cmake" to "cmake",
        "nginx" to "nginx",
        "ini" to "ini",
        "cfg" to "ini",
        "properties" to "properties",
        "diff" to "diff",
        "patch" to "diff",
        "regex" to "regex",
        "tex" to "latex",
        "rst" to "rest",
        "vim" to "vim",
    )

    /** 纯文本类扩展名（无语法高亮）。 */
    private val TEXT_EXTENSIONS = setOf(
        "txt",
        "log",
        "text",
        "csv",
        "tsv",
        "mdx",
        "conf",
        "hosts",
        "srt",
        "vtt",
        "lrc",
        "pem",
        "crt",
        "key",
        "nfo",
    )

    fun classify(fileName: String): Classification {
        val name = fileName.trim().substringAfterLast('/').substringAfterLast('\\')
        if (name.isEmpty()) return Classification(Category.OTHER)

        if (name.equals("skill.md", ignoreCase = true)) return Classification(Category.MARKDOWN)

        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension == SKILL_PACKAGE_EXTENSION) return Classification(Category.SKILL_PACKAGE)

        if (extension == "docx") return Classification(Category.DOCX)

        if (extension == "html" || extension == "htm") {
            return Classification(Category.HTML, prismLanguage = "html")
        }

        if (name.lowercase() in SPECIAL_TEXT_FILE_NAMES) return Classification(Category.TEXT)

        if (extension in TEXT_EXTENSIONS) return Classification(Category.TEXT)

        if (extension == "md" || extension == "markdown") return Classification(Category.MARKDOWN)

        CODE_EXTENSIONS[extension]?.let { return Classification(Category.CODE, prismLanguage = it) }

        return Classification(Category.OTHER)
    }

    /** 该文件名是否应当尝试用应用内查看器打开。 */
    fun shouldUseBuiltInViewer(fileName: String): Boolean =
        classify(fileName).category != Category.OTHER
}
