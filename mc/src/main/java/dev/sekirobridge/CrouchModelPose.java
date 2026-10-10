package dev.sekirobridge;

import net.minecraft.client.render.entity.model.PlayerEntityModel;

/** Adds vanilla's crouch offsets after weapon animation, from a standing base. */
public final class CrouchModelPose {
    private CrouchModelPose() {}
    public static void apply(PlayerEntityModel<?> model) {
        model.body.pitch += .5f;
        model.rightArm.pitch += .4f;
        model.leftArm.pitch += .4f;
        model.rightLeg.pivotZ += 4;
        model.leftLeg.pivotZ += 4;
        model.rightLeg.pivotY += .2f;
        model.leftLeg.pivotY += .2f;
        model.head.pivotY += 4.2f;
        model.body.pivotY += 3.2f;
        model.rightArm.pivotY += 3.2f;
        model.leftArm.pivotY += 3.2f;
        model.hat.copyTransform(model.head);
        model.jacket.copyTransform(model.body);
        model.rightSleeve.copyTransform(model.rightArm);
        model.leftSleeve.copyTransform(model.leftArm);
        model.rightPants.copyTransform(model.rightLeg);
        model.leftPants.copyTransform(model.leftLeg);
    }
}
