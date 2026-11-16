package com.jidan.receiving.core

/** 建单四项里选中的一项：后端的 id 与给人看的名字。 */
data class Pick(val id: Long, val name: String)

/**
 * 进行中的单据（开发规格 9.2、v7 `APP-11`，原型的 `S`）：建单四项、批次号、皮重、流水。
 * 手录记号、秤原始读数、打印标记都在流水 [Box] 上，不另造一份。
 *
 * 不可变：每个操作返回新的 `Session`，ViewModel 放进 StateFlow 即可；
 * 每次变化后 [toJson] 写成本地草稿，进程被回收后 [fromJson] 读回，读回的值与原来 `==`。
 * 不做离线与补传：草稿只为不丢已称的箱，提交成功或放弃整单后由 `:app` 删除。
 *
 * 状态机不持有流水（见 [WeighingMachine]），它记下的 [WeighOutcome] 交给 [apply] 加进来。
 *
 * @param groupSeq 已发出的称重组号。删掉一组后不回收，新组继续往后编；手工加的箱也占一个组号。
 */
data class Session(
    val batchNo: String,
    val farmer: Pick,
    val product: Pick,
    val spec: Pick,
    val warehouse: Pick,
    val tare: Tare? = null,
    val boxes: List<Box> = emptyList(),
    val groupSeq: Int = 0,
) {
    val count: Int get() = boxes.size

    /** 累计净重（手录值按录入值算）。 */
    val totalNet: Kg get() = boxes.map { it.net }.sum()

    /** 还没打出标签的箱，补打用。 */
    val unprinted: List<Box> get() = boxes.filter { !it.printed }

    fun box(seq: Int): Box = boxes.first { it.seq == seq }

    /** 设（或换）皮重。已记的箱各自带着当时的皮重，不受影响。 */
    fun withTare(t: Tare?): Session = copy(tare = t)

    /** 把状态机记下的一次称重加进流水：新增 k 条，或替换指定的一箱（重称）。 */
    fun apply(outcome: WeighOutcome): Session = when (outcome) {
        is WeighOutcome.Recorded -> {
            val g = groupSeq + 1
            copy(
                boxes = SplitBoxes.append(boxes, outcome.weighing.toBoxes(g, tare?.name ?: "")),
                groupSeq = g,
            )
        }
        is WeighOutcome.Replaced -> {
            val old = box(outcome.seq)
            val fresh = outcome.weighing.replacing(old, tare?.name ?: "")
            copy(boxes = boxes.map { if (it.seq == old.seq) fresh else it })
        }
    }

    /**
     * 秤不可用时手工加一箱：箱数恒为 1，带手录记号，原读数为空（`APP-11`）。
     * 皮重取当前选的那只箱，没选就是 0。
     */
    fun addManual(net: Kg): Session {
        requirePositive(net)
        val g = groupSeq + 1
        val added = SplitBoxes.split(
            net = net, k = 1, tare = tare?.unit ?: Kg.ZERO, tareName = tare?.name ?: "",
            group = g, manual = true,
        )
        return copy(boxes = SplitBoxes.append(boxes, added), groupSeq = g)
    }

    /**
     * 对第 [seq] 箱手工录入净重：带手录记号，原读数记成改之前秤给的净重，打印标记清掉（原标签作废）。
     * 已经手录过的箱再改，原读数不变，仍是秤最初的读数；手工加的箱原读数本来就空，改多少次都是空（附录 C 第 16 条）。
     */
    fun editManual(seq: Int, net: Kg): Session {
        requirePositive(net)
        return mapBox(seq) { b ->
            b.copy(net = net, manual = true, raw = if (b.manual) b.raw else b.net, printed = false)
        }
    }

    /** 删除第 [seq] 箱所在的整组，后面的序号顺延。 */
    fun delete(seq: Int): Session = copy(boxes = SplitBoxes.deleteGroup(boxes, seq))

    /**
     * 第 [seq] 箱所在的那次称重能不能改箱数：必须是秤称出来的，且组里没有手录过、没有单箱重称过
     * （否则按总重重新均摊会冲掉手录值或重称的读数）。
     */
    fun canRegroup(seq: Int): Boolean {
        val first = box(seq)
        return first.gross != null && first.unit != null &&
            boxes.filter { it.group == first.group }.all {
                !it.manual && it.gross == first.gross && it.unit == first.unit
            }
    }

    /** 识别错了：第 [seq] 箱所在的那次称重改按 [k] 箱重新均摊（`canRegroup` 为 false 时不可用）。 */
    fun regroup(seq: Int, k: Int): Session {
        require(k >= 1) { "箱数必须大于 0" }
        require(canRegroup(seq)) { "第 $seq 箱所在的那次称重不能改箱数" }
        return copy(boxes = SplitBoxes.regroup(boxes, seq, k))
    }

    /** 标上（或清掉）第 [seq] 箱的打印标记。 */
    fun markPrinted(seq: Int, printed: Boolean = true): Session = mapBox(seq) { it.copy(printed = printed) }

    private fun mapBox(seq: Int, f: (Box) -> Box): Session {
        box(seq)
        return copy(boxes = boxes.map { if (it.seq == seq) f(it) else it })
    }

    private fun requirePositive(net: Kg) = require(net > Kg.ZERO) { "净重必须大于 0" }

    /**
     * 草稿 JSON。重量一律写 0.01kg 的整数（同 [Kg.cents]），这是本地文件，不是接口，不转 kg 小数。
     */
    fun toJson(): String = MiniJson.write(
        mapOf(
            "batchNo" to batchNo,
            "farmer" to farmer.toMap(),
            "product" to product.toMap(),
            "spec" to spec.toMap(),
            "warehouse" to warehouse.toMap(),
            "tare" to tare?.let { mapOf("unit" to it.unit.cents, "name" to it.name) },
            "groupSeq" to groupSeq,
            "boxes" to boxes.map { it.toMap() },
        ),
    )

    companion object {
        /** 读回 [toJson] 写的草稿。读不懂就抛异常，`:app` 当没有草稿处理。 */
        fun fromJson(text: String): Session {
            val m = MiniJson.parse(text).asMap()
            return Session(
                batchNo = m.str("batchNo"),
                farmer = m.map("farmer").toPick(),
                product = m.map("product").toPick(),
                spec = m.map("spec").toPick(),
                warehouse = m.map("warehouse").toPick(),
                tare = m.mapOrNull("tare")?.let { Tare(Kg(it.int("unit")), it.str("name")) },
                boxes = m.list("boxes").map { it.asMap().toBox() },
                groupSeq = m.int("groupSeq"),
            )
        }

        private fun Pick.toMap() = mapOf("id" to id, "name" to name)

        private fun Box.toMap() = mapOf(
            "seq" to seq, "group" to group, "net" to net.cents, "tare" to tare.cents, "tareName" to tareName,
            "gross" to gross?.cents, "unit" to unit?.cents, "manual" to manual, "raw" to raw?.cents,
            "printed" to printed,
        )

        private fun Map<String, Any?>.toPick() = Pick(long("id"), str("name"))

        private fun Map<String, Any?>.toBox() = Box(
            seq = int("seq"), group = int("group"), net = Kg(int("net")), tare = Kg(int("tare")),
            tareName = str("tareName"), gross = intOrNull("gross")?.let(::Kg), unit = intOrNull("unit")?.let(::Kg),
            manual = bool("manual"), raw = intOrNull("raw")?.let(::Kg), printed = bool("printed"),
        )

        @Suppress("UNCHECKED_CAST")
        private fun Any?.asMap(): Map<String, Any?> =
            this as? Map<String, Any?> ?: throw IllegalArgumentException("草稿格式不对：应是对象")

        private fun Map<String, Any?>.field(key: String): Any? {
            require(containsKey(key)) { "草稿缺少 $key" }
            return get(key)
        }

        private fun Map<String, Any?>.str(key: String) =
            field(key) as? String ?: throw IllegalArgumentException("草稿里 $key 应是文字")

        private fun Map<String, Any?>.long(key: String) =
            field(key) as? Long ?: throw IllegalArgumentException("草稿里 $key 应是整数")

        private fun Map<String, Any?>.int(key: String) = Math.toIntExact(long(key))

        private fun Map<String, Any?>.intOrNull(key: String) = if (field(key) == null) null else int(key)

        private fun Map<String, Any?>.bool(key: String) =
            field(key) as? Boolean ?: throw IllegalArgumentException("草稿里 $key 应是布尔值")

        private fun Map<String, Any?>.map(key: String) = field(key).asMap()

        private fun Map<String, Any?>.mapOrNull(key: String) = field(key)?.asMap()

        private fun Map<String, Any?>.list(key: String) =
            field(key) as? List<*> ?: throw IllegalArgumentException("草稿里 $key 应是数组")
    }
}
