package pw.binom.db.duckdb.adapter

/**
 * Перевод именованных параметров `:name` в позиционные `?`.
 *
 * ## Почему это обязанность драйвера
 *
 * `korm` не знает, какая СУБД под ним, и генерирует SQL с именованными
 * параметрами — `INSERT INTO t (id, name) VALUES (:id, :name)`. Перевод в
 * синтаксис конкретного движка (`$1`, `?`) — работа драйвера, и только его.
 * Так одна и та же генерация годится и для Postgres, и для SQLite, и для
 * DuckDB.
 *
 * ## Что здесь важно и легко испортить
 *
 * 1. **Двойное двоеточие — это приведение типа, а не параметр.** DuckDB
 *    использует `::` (`u::VARCHAR`, `'1'::INTEGER`), и наивная замена
 *    `:x` превратила бы `u::VARCHAR` в мусор. Поэтому `::` пропускается
 *    как есть.
 * 2. **Двоеточие внутри строкового литерала — не параметр.**
 *    `WHERE t = 'a:b'` должен остаться нетронутым. Учитываются и `''`
 *    внутри литерала, и `"quoted identifiers"`.
 * 3. **Порядок появления = порядок значений.** `korm` подаёт значения
 *    ровно в порядке появления имён в SQL, поэтому замена на `?` слева
 *    направо сохраняет соответствие без таблицы перестановок.
 *
 * ## Чего здесь нет
 *
 * Повторного использования имени (`:id ... :id` дважды) — `korm` так не
 * делает: каждое имя в запросе встречается один раз. Если понадобится,
 * это добавляется словарём «имя → номер» за пару строк, но тогда `?`
 * перестанет годиться и понадобится `$N`.
 */
internal object SqlParameters {

    class Parsed(
        /** SQL с `?` вместо `:name`. */
        val sql: String,
        /** Имена в порядке появления — для диагностики и отладки. */
        val names: List<String>,
    )

    private fun isNameStart(c: Char) = c.isLetter() || c == '_'

    private fun isNamePart(c: Char) = c.isLetterOrDigit() || c == '_'

    fun parse(sql: String): Parsed {
        val out = StringBuilder(sql.length + 16)
        val names = ArrayList<String>()
        var i = 0
        val n = sql.length
        while (i < n) {
            val c = sql[i]
            when {
                // Строковый литерал: 'текст', внутри '' — экранированная кавычка.
                c == '\'' -> {
                    out.append(c)
                    i++
                    while (i < n) {
                        val ch = sql[i]
                        out.append(ch)
                        i++
                        if (ch == '\'') {
                            if (i < n && sql[i] == '\'') {
                                out.append('\'')
                                i++
                            } else {
                                break
                            }
                        }
                    }
                }

                // Идентификатор в кавычках: "имя с :двоеточием".
                c == '"' -> {
                    out.append(c)
                    i++
                    while (i < n) {
                        val ch = sql[i]
                        out.append(ch)
                        i++
                        if (ch == '"') break
                    }
                }

                // Однострочный комментарий.
                c == '-' && i + 1 < n && sql[i + 1] == '-' -> {
                    while (i < n && sql[i] != '\n') {
                        out.append(sql[i])
                        i++
                    }
                }

                // Блочный комментарий.
                c == '/' && i + 1 < n && sql[i + 1] == '*' -> {
                    out.append("/*")
                    i += 2
                    while (i < n) {
                        if (sql[i] == '*' && i + 1 < n && sql[i + 1] == '/') {
                            out.append("*/")
                            i += 2
                            break
                        }
                        out.append(sql[i])
                        i++
                    }
                }

                // Приведение типа `::` — не параметр, пропускается целиком.
                c == ':' && i + 1 < n && sql[i + 1] == ':' -> {
                    out.append("::")
                    i += 2
                }

                // Именованный параметр.
                c == ':' && i + 1 < n && isNameStart(sql[i + 1]) -> {
                    val start = i + 1
                    var j = start
                    while (j < n && isNamePart(sql[j])) j++
                    names += sql.substring(start, j)
                    out.append('?')
                    i = j
                }

                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return Parsed(out.toString(), names)
    }
}
