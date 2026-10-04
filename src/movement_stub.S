.text
.globl scMovementEntry
scMovementEntry:
    pushfq
    pushq %rax
    pushq %rcx
    pushq %rdx
    pushq %r8
    pushq %r9
    pushq %r10
    pushq %r11
    pushq %r12
    movq %rsp, %r12
    andq $-16, %rsp
    subq $160, %rsp
    movups %xmm0, 32(%rsp)
    movups %xmm1, 48(%rsp)
    movups %xmm2, 64(%rsp)
    movups %xmm3, 80(%rsp)
    movups %xmm4, 96(%rsp)
    movups %xmm5, 112(%rsp)
    movups %xmm6, 128(%rsp)
    stmxcsr 144(%rsp)
    movq %rsi, %rcx
    leaq 128(%rsp), %rdx
    callq *scMovementHandler(%rip)
    movups 32(%rsp), %xmm0
    movups 48(%rsp), %xmm1
    movups 64(%rsp), %xmm2
    movups 80(%rsp), %xmm3
    movups 96(%rsp), %xmm4
    movups 112(%rsp), %xmm5
    movups 128(%rsp), %xmm6
    ldmxcsr 144(%rsp)
    movq %r12, %rsp
    popq %r12
    popq %r11
    popq %r10
    popq %r9
    popq %r8
    popq %rdx
    popq %rcx
    popq %rax
    popfq
    jmpq *scMovementContinue(%rip)
.text
.globl scCameraEntry
scCameraEntry:
    pushfq
    pushq %rax
    pushq %rcx
    pushq %rdx
    pushq %r8
    pushq %r9
    pushq %r10
    pushq %r11
    pushq %r12
    movq %rsp, %r12
    andq $-16, %rsp
    subq $160, %rsp
    movups %xmm0, 32(%rsp)
    movups %xmm1, 48(%rsp)
    movups %xmm2, 64(%rsp)
    movups %xmm3, 80(%rsp)
    movups %xmm4, 96(%rsp)
    movups %xmm5, 112(%rsp)
    movups %xmm6, 128(%rsp)
    stmxcsr 144(%rsp)
    movq %rdi, %rcx
    leaq 128(%rsp), %rdx
    callq *scCameraHandler(%rip)
    movups 32(%rsp), %xmm0
    movups 48(%rsp), %xmm1
    movups 64(%rsp), %xmm2
    movups 80(%rsp), %xmm3
    movups 96(%rsp), %xmm4
    movups 112(%rsp), %xmm5
    movups 128(%rsp), %xmm6
    ldmxcsr 144(%rsp)
    movq %r12, %rsp
    popq %r12
    popq %r11
    popq %r10
    popq %r9
    popq %r8
    popq %rdx
    popq %rcx
    popq %rax
    popfq
    jmpq *scCameraContinue(%rip)
